package com.mentalbridge.community.chat;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatGroupTransactions {
    private final ChatGroupAccess access;
    private final CommunityChatProfiles profiles;
    private final ChatAccess directAccess;
    private final ChatConversationRepository conversations;
    private final ChatMembershipRepository members;
    private final ChatInvitationRepository invitations;
    private final ChatCommandRepository commands;
    private final ChatCrypto crypto;
    private final ChatFacts facts;
    private final ChatGroupProperties properties;
    private final Clock clock;
    ChatGroupTransactions(ChatGroupAccess access, CommunityChatProfiles profiles, ChatAccess directAccess,
            ChatConversationRepository conversations, ChatMembershipRepository members, ChatInvitationRepository invitations,
            ChatCommandRepository commands, ChatCrypto crypto, ChatFacts facts, ChatGroupProperties properties, Clock clock, ChatPrivacyRepository privacy) {
        this.access = access; this.profiles = profiles; this.directAccess = directAccess; this.conversations = conversations;
        this.members = members; this.invitations = invitations; this.commands = commands; this.crypto = crypto;
        this.facts = facts; this.properties = properties; this.clock = clock; this.privacy = privacy;
    }
    @Transactional
    ChatGroupModels.Group create(UUID subject, String name, UUID key, UUID correlation) {
        var actor = directAccess.actor(subject);
        var replay = commands.findByActorProfileIdAndOperationAndIdempotencyKey(actor.id(), "CREATE_GROUP", key);
        if (replay.isPresent()) {
            var command = replay.orElseThrow();
            if (!crypto.matches(command.requestFingerprint, "CREATE_GROUP", name)) throw ChatException.conflict();
            var group = conversations.lock(command.conversationId).orElseThrow(ChatException::target);
            members.findByConversationIdAndProfileIdAndDepartedAtIsNull(group.id, actor.id()).orElseThrow(ChatException::target);
            return access.group(group);
        }
        if (commands.countByActorProfileIdAndOperationAndCreatedAtAfter(actor.id(), "CREATE_GROUP", clock.instant().minus(Duration.ofDays(1))) >= 5) throw ChatException.limited();
        var group = new ChatConversationEntity(); group.id = UUID.randomUUID(); group.kind = "GROUP";
        group.state = "ACTIVE"; group.createdAt = clock.instant(); group.activatedAt = group.createdAt;
        group.managementVersion = 1; group.memberLimit = properties.memberLimit();
        group.nameCiphertext = crypto.seal("group-name:" + group.id, name);
        conversations.saveAndFlush(group);
        members.save(new ChatMembershipEntity(group, actor.id(), "OWNER", clock.instant()));
        commands.save(new ChatCommandEntity(actor.id(), "CREATE_GROUP", key, crypto.fingerprint("CREATE_GROUP", name), "GROUP", group.id, group.id, clock.instant()));
        facts.invalidated(group, correlation);
        return access.group(group);
    }
    @Transactional
    ChatGroupModels.Versioned<ChatGroupModels.Invitation> invite(UUID conversation, ChatGroupAccess.Authorization authorization,
            UUID invitee, long expected, UUID key, UUID correlation) {
        var context = access.lock(conversation, authorization, true); access.active(context);
        if (!context.member().manager() || invitee.equals(context.actor().id()) || !access.visible(context, invitee)) throw ChatException.target();
        var normalized = conversation + ":" + invitee;
        var replay = commands.findByActorProfileIdAndOperationAndIdempotencyKey(context.actor().id(), "INVITE_GROUP", key);
        if (replay.isPresent()) {
            var command = replay.orElseThrow();
            if (!crypto.matches(command.requestFingerprint, "INVITE_GROUP", normalized)) throw ChatException.conflict();
            var invite = invitations.findById(command.targetId).orElseThrow(ChatException::target);
            if (!valid(invite, context)) throw ChatException.target();
            return new ChatGroupModels.Versioned<>(invitation(invite, context), context.group().managementVersion);
        }
        access.version(context, expected);
        if (context.members().stream().anyMatch(m -> m.profileId.equals(invitee))) throw ChatException.notAllowed();
        if (invitations.existsByConversationIdAndInviteeProfileIdAndState(conversation, invitee, "DECLINED")) throw ChatException.notAllowed();
        for (var old : invitations.findByConversationIdAndState(conversation, "PENDING")) {
            if (!old.expiresAt.isAfter(clock.instant())) old.finish("EXPIRED", clock.instant());
            else if (old.inviteeProfileId.equals(invitee)) throw ChatException.notAllowed();
        }
        if (invitations.countByInviterProfileIdAndCreatedAtAfter(context.actor().id(), clock.instant().minus(Duration.ofDays(1))) >= 20) throw ChatException.limited();
        invitations.flush();
        var invite = invitations.saveAndFlush(new ChatInvitationEntity(context.member(), invitee, clock.instant()));
        commands.save(new ChatCommandEntity(context.actor().id(), "INVITE_GROUP", key, crypto.fingerprint("INVITE_GROUP", normalized), "INVITATION", invite.id, conversation, clock.instant()));
        context.group().managementVersion++; context.group().eventVersion++;
        facts.publish(context.group(), "GROUP_INVITED", invite.id, context.actor().id(), context.eligible().get(invitee).subject(), correlation, null);
        return new ChatGroupModels.Versioned<>(invitation(invite, context), context.group().managementVersion);
    }
    @Transactional
    ChatGroupModels.Group decide(UUID id, UUID conversation, ChatGroupAccess.Authorization authorization, String decision, long expected, UUID correlation) {
        var context = access.lock(conversation, authorization, false);
        var invite = invitations.findById(id).filter(i -> i.conversationId.equals(conversation) && i.inviteeProfileId.equals(context.actor().id())).orElseThrow(ChatException::target);
        var desired = decision.equals("ACCEPT") ? "ACCEPTED" : "DECLINED";
        if (!invite.state.equals("PENDING")) {
            if (!invite.state.equals(desired)) throw ChatException.notAllowed();
            if (desired.equals("DECLINED")) return null;
            if (context.member() == null || !context.member().id.equals(invite.acceptedMembershipId)) throw ChatException.target();
            return access.group(context.group());
        }
        if (!valid(invite, context)) throw ChatException.target();
        if (invite.version != expected) throw ChatException.version();
        if (desired.equals("DECLINED")) { invite.finish(desired, clock.instant()); return null; }
        if (context.member() != null || context.members().size() >= context.group().memberLimit) throw ChatException.notAllowed();
        var membership = members.save(new ChatMembershipEntity(context.group(), context.actor().id(), "MEMBER", clock.instant()));
        invite.acceptedMembershipId = membership.id; invite.finish("ACCEPTED", clock.instant());
        changed(context, correlation);
        return access.group(context.group());
    }
    @Transactional
    ChatGroupModels.Versioned<ChatGroupModels.Member> role(UUID conversation, ChatGroupAccess.Authorization authorization, UUID target, String role, long expected, UUID correlation) {
        var context = access.lock(conversation, authorization, true); access.active(context); access.owner(context); access.version(context, expected);
        var member = context.member(target);
        if (!context.eligible().containsKey(target)) throw ChatException.target();
        if (member.role.equals("OWNER")) throw ChatException.notAllowed();
        if (!member.role.equals(role)) {
            if (member.manager()) revokeInviter(member.id);
            member.role = role; changed(context, correlation);
        }
        return new ChatGroupModels.Versioned<>(new ChatGroupModels.Member(access.peer(context, target), member.role, member.joinedAt), context.group().managementVersion);
    }
    @Transactional
    ChatGroupModels.Group transfer(UUID conversation, ChatGroupAccess.Authorization authorization, UUID target, long expected, UUID correlation) {
        var context = access.lock(conversation, authorization, true); access.active(context); access.owner(context); access.version(context, expected);
        var member = context.member(target);
        if (!member.role.equals("ADMIN") || !context.eligible().containsKey(target)) throw ChatException.target();
        context.member().role = "ADMIN"; member.role = "OWNER"; changed(context, correlation);
        return access.group(context.group());
    }
    @Transactional
    void remove(UUID conversation, ChatGroupAccess.Authorization authorization, UUID target, long expected, UUID correlation) {
        var context = access.lock(conversation, authorization, true); access.version(context, expected);
        var targetMember = context.member(target); boolean self = target.equals(context.actor().id());
        if (!context.group().state.equals("ACTIVE")) { if (!self) throw ChatException.notAllowed(); }
        else if (targetMember.role.equals("OWNER") || (!self && !context.member().role.equals("OWNER")
                && !(context.member().role.equals("ADMIN") && targetMember.role.equals("MEMBER")))) throw ChatException.target();
        targetMember.departedAt = clock.instant(); revokeInviter(targetMember.id); changed(context, correlation);
    }
    @Transactional
    void archive(UUID conversation, ChatGroupAccess.Authorization authorization, long expected, UUID correlation) {
        var context = access.lock(conversation, authorization, true); access.owner(context); access.version(context, expected);
        if (context.group().state.equals("ARCHIVED")) return;
        context.group().state = "ARCHIVED";
        invitations.findByConversationIdAndState(conversation, "PENDING").forEach(i -> i.finish("REVOKED", clock.instant()));
        changed(context, correlation);
    }
    boolean valid(ChatInvitationEntity invite, ChatGroupAccess.Context context) {
        return invite.state.equals("PENDING") && invite.expiresAt.isAfter(clock.instant()) && context.group().state.equals("ACTIVE")
                && context.members().stream().anyMatch(m -> m.id.equals(invite.inviterMembershipId) && m.manager())
                && context.eligible().containsKey(invite.inviterProfileId) && context.eligible().containsKey(invite.inviteeProfileId)
                && !directAccessBlocked(invite.inviterProfileId, invite.inviteeProfileId);
    }
    private boolean directAccessBlocked(UUID a, UUID b) { return privacy.blocked(a, b); }
    private final ChatPrivacyRepository privacy;
    ChatGroupModels.Invitation invitation(ChatInvitationEntity invite, ChatGroupAccess.Context context) {
        var inviter = context.eligible().get(invite.inviterProfileId);
        return new ChatGroupModels.Invitation(invite.id, invite.conversationId, access.name(context.group()),
                new ChatModels.Peer("AVAILABLE", inviter.id(), inviter.displayName()), invite.state, invite.expiresAt, Long.toString(invite.version));
    }
    private void revokeInviter(UUID membership) {
        var member = members.findById(membership).orElseThrow();
        invitations.findByConversationIdAndState(member.conversationId, "PENDING").stream()
                .filter(i -> i.inviterMembershipId.equals(membership)).forEach(i -> i.finish("REVOKED", clock.instant()));
    }
    private void changed(ChatGroupAccess.Context context, UUID correlation) {
        context.group().managementVersion++; facts.invalidated(context.group(), correlation);
    }
}
