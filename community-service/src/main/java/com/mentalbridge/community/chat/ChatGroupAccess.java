package com.mentalbridge.community.chat;

import java.util.*;
import java.util.stream.Collectors;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import com.mentalbridge.community.feed.CommunityChatProfiles.Profile;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ChatGroupAccess {
    private final CommunityChatProfiles profiles;
    private final ChatMembershipRepository members;
    private final ChatConversationRepository conversations;
    private final ChatPrivacyRepository privacy;
    private final ChatAccountAuthorizer accounts;
    private final ChatCrypto crypto;
    private final EntityManager entityManager;
    ChatGroupAccess(CommunityChatProfiles profiles, ChatMembershipRepository members, ChatConversationRepository conversations,
            ChatPrivacyRepository privacy, ChatAccountAuthorizer accounts, ChatCrypto crypto, EntityManager entityManager) {
        this.profiles = profiles; this.members = members; this.conversations = conversations; this.privacy = privacy;
        this.accounts = accounts; this.crypto = crypto; this.entityManager = entityManager;
    }
    Authorization authorize(UUID subject, UUID conversation, UUID extra, UUID correlation) {
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var ids = new HashSet<UUID>(); ids.add(actor.id()); if (extra != null) ids.add(extra);
        members.findByConversationIdAndDepartedAtIsNullOrderById(conversation).forEach(value -> ids.add(value.profileId));
        var known = ids.stream().map(profiles::byId).flatMap(Optional::stream).toList();
        var active = accounts.active(known.stream().map(Profile::subject).toList(), correlation);
        if (!active.contains(subject)) throw ChatException.target();
        return new Authorization(actor.id(), known.stream().map(Profile::id).toList(),
                known.stream().filter(p -> active.contains(p.subject())).map(Profile::id).collect(Collectors.toSet()));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    Context lock(UUID conversation, Authorization authorization, boolean memberRequired) {
        var current = profiles.lock(authorization.profiles()).stream().collect(Collectors.toMap(Profile::id, p -> p));
        var actor = current.get(authorization.actor());
        if (actor == null || !actor.active() || privacy.restricted(List.of(actor.id()))) throw ChatException.target();
        var group = conversations.lock(conversation).filter(c -> c.kind.equals("GROUP")).orElseThrow(ChatException::target);
        entityManager.refresh(group);
        var roster = members.findByConversationIdAndDepartedAtIsNullOrderById(group.id);
        var membership = roster.stream().filter(m -> m.profileId.equals(actor.id())).findFirst().orElse(null);
        if (memberRequired && membership == null) throw ChatException.target();
        var eligible = current.values().stream().filter(p -> p.active() && authorization.active().contains(p.id())
                && !privacy.restricted(List.of(p.id()))).collect(Collectors.toMap(Profile::id, p -> p));
        return new Context(group, actor, membership, roster, eligible);
    }
    boolean visible(Context context, UUID peer) {
        return context.eligible().containsKey(peer) && !privacy.blocked(context.actor().id(), peer);
    }
    ChatModels.Peer peer(Context context, UUID profile) {
        if (!visible(context, profile)) return new ChatModels.Peer("UNAVAILABLE", null, null);
        var value = context.eligible().get(profile);
        return new ChatModels.Peer("AVAILABLE", value.id(), value.displayName());
    }
    String name(ChatConversationEntity group) { return crypto.open("group-name:" + group.id, group.nameCiphertext); }
    ChatGroupModels.Group group(ChatConversationEntity group) {
        return new ChatGroupModels.Group(group.id, "GROUP", name(group), group.state, group.memberLimit, Long.toString(group.managementVersion));
    }
    void version(Context context, long expected) { if (context.group().managementVersion != expected) throw ChatException.version(); }
    void active(Context context) { if (!context.group().state.equals("ACTIVE")) throw ChatException.notAllowed(); }
    void owner(Context context) { if (!context.member().role.equals("OWNER")) throw ChatException.target(); }
    record Authorization(UUID actor, List<UUID> profiles, Set<UUID> active) {}
    record Context(ChatConversationEntity group, Profile actor, ChatMembershipEntity member,
            List<ChatMembershipEntity> members, Map<UUID, Profile> eligible) {
        ChatMembershipEntity member(UUID profile) { return members.stream().filter(m -> m.profileId.equals(profile)).findFirst().orElseThrow(ChatException::target); }
    }
}
