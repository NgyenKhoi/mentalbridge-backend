package com.mentalbridge.community.chat;

import java.text.Normalizer;
import java.time.Clock;
import java.util.*;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;

@Service
class CommunityGroupService {
    private final ChatGroupAccess access;
    private final ChatGroupTransactions transactions;
    private final ChatGroupReads reads;
    private final ChatAccountAuthorizer accounts;
    private final ChatInvitationRepository invitations;
    private final CommunityChatProfiles profiles;
    private final ChatCursors cursors;
    private final ChatPrivacyRepository privacy;
    private final Clock clock;
    CommunityGroupService(ChatGroupAccess access, ChatGroupTransactions transactions, ChatGroupReads reads, ChatAccountAuthorizer accounts,
            ChatInvitationRepository invitations, CommunityChatProfiles profiles, ChatCursors cursors, ChatPrivacyRepository privacy, Clock clock) {
        this.access = access; this.transactions = transactions; this.reads = reads; this.accounts = accounts;
        this.invitations = invitations; this.profiles = profiles; this.cursors = cursors; this.privacy = privacy; this.clock = clock;
    }
    ChatGroupModels.Group create(UUID subject, String name, UUID key, UUID correlation) {
        if (name == null || name.isBlank() || name.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) throw ChatException.invalid();
        name = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
        if (name.codePointCount(0, name.length()) > 80) throw ChatException.invalid();
        accounts.require(List.of(subject), correlation); return transactions.create(subject, name, key, correlation);
    }
    ChatGroupModels.Versioned<ChatGroupModels.Invitation> invite(UUID subject, UUID group, UUID invitee, long version, UUID key, UUID correlation) {
        if (invitee == null) throw ChatException.invalid();
        return transactions.invite(group, access.authorize(subject, group, invitee, correlation), invitee, version, key, correlation);
    }
    ChatGroupModels.Group decide(UUID subject, UUID id, String decision, long version, UUID correlation) {
        if (!List.of("ACCEPT", "DECLINE").contains(decision == null ? "" : decision)) throw ChatException.invalid();
        var invite = invitations.findById(id).orElseThrow(ChatException::target);
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        if (!actor.id().equals(invite.inviteeProfileId)) throw ChatException.target();
        return transactions.decide(id, invite.conversationId, access.authorize(subject, invite.conversationId, invite.inviterProfileId, correlation), decision, version, correlation);
    }
    ChatGroupModels.Versioned<ChatGroupModels.Member> role(UUID subject, UUID group, UUID target, String role, long version, UUID correlation) {
        if (!List.of("ADMIN", "MEMBER").contains(role == null ? "" : role)) throw ChatException.invalid();
        return transactions.role(group, access.authorize(subject, group, target, correlation), target, role, version, correlation);
    }
    ChatGroupModels.Group transfer(UUID subject, UUID group, UUID target, long version, UUID correlation) {
        if (target == null) throw ChatException.invalid();
        return transactions.transfer(group, access.authorize(subject, group, target, correlation), target, version, correlation);
    }
    void remove(UUID subject, UUID group, UUID target, long version, UUID correlation) {
        transactions.remove(group, access.authorize(subject, group, target, correlation), target, version, correlation);
    }
    void archive(UUID subject, UUID group, long version, UUID correlation) {
        transactions.archive(group, access.authorize(subject, group, null, correlation), version, correlation);
    }
    ChatModels.Page<ChatGroupModels.Member> members(UUID subject, UUID group, String cursor, int limit, UUID correlation) {
        ChatModels.limit(limit); return reads.members(group, access.authorize(subject, group, null, correlation), cursor, limit);
    }
    ChatModels.Page<ChatGroupModels.Invitation> incoming(UUID subject, String cursor, int limit, UUID correlation) {
        ChatModels.limit(limit); accounts.require(List.of(subject), correlation);
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        if (!actor.active() || privacy.restricted(List.of(actor.id()))) throw ChatException.target();
        var epoch = privacy.findById(actor.id()).map(p -> p.visibilityEpoch).orElse(0L);
        var before = cursors.decode(actor.id(), "invitations", epoch, cursor);
        var selected = new ArrayList<ChatGroupModels.Invitation>(); var positions = new HashMap<UUID, ChatInvitationEntity>();
        while (selected.size() <= limit) {
            var batch = invitations.incoming(actor.id(), clock.instant(), before.time(), before.id());
            if (batch.isEmpty()) break;
            var profileIds = new HashSet<UUID>(); profileIds.add(actor.id()); batch.forEach(i -> profileIds.add(i.inviterProfileId));
            var known = profiles.byIds(profileIds);
            var active = accounts.active(known.stream().map(p -> p.subject()).toList(), correlation);
            if (!active.contains(subject)) throw ChatException.target();
            var eligible = known.stream().filter(p -> active.contains(p.subject())).map(p -> p.id()).collect(java.util.stream.Collectors.toSet());
            for (var invite : batch) {
                var auth = new ChatGroupAccess.Authorization(actor.id(), List.of(actor.id(), invite.inviterProfileId), eligible);
                reads.invitation(invite.id, invite.conversationId, auth).ifPresent(value -> { selected.add(value); positions.put(value.invitationId(), invite); });
            }
            var last = batch.getLast(); before = new ChatCursors.Position(last.createdAt, last.id);
            if (batch.size() < 50) break;
        }
        var items = selected.stream().limit(limit).toList();
        var last = items.isEmpty() ? null : positions.get(items.getLast().invitationId());
        return new ChatModels.Page<>(items, selected.size() > limit ? cursors.encode(actor.id(), "invitations", epoch, last.createdAt, last.id) : null);
    }
}
