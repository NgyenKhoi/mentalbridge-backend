package com.mentalbridge.community.chat;

import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import com.mentalbridge.community.feed.CommunityChatProfiles.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatPresenceTransactions {
    private final CommunityChatProfiles profiles;
    private final ChatAccess access;
    private final ChatConversationRepository conversations;
    private final ChatGroupAccess groups;

    ChatPresenceTransactions(CommunityChatProfiles profiles, ChatAccess access,
            ChatConversationRepository conversations, ChatGroupAccess groups) {
        this.profiles = profiles; this.access = access; this.conversations = conversations; this.groups = groups;
    }

    @Transactional
    Projection direct(UUID subject, UUID conversation, UUID peerId) {
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var pair = access.pair(actor.id(), peerId);
        var current = conversations.lock(conversation).orElseThrow(ChatException::target);
        if (!current.kind.equals("DIRECT") || !current.state.equals("ACTIVE")
                || !current.other(actor.id()).equals(peerId)) throw ChatException.target();
        var peer = pair.stream().filter(profile -> profile.id().equals(peerId)).findFirst().orElseThrow(ChatException::target);
        return new Projection(visible(peer) ? List.of(peer(peer)) : List.of());
    }

    @Transactional
    Projection group(UUID conversation, ChatGroupAccess.Authorization authorization) {
        var context = groups.lock(conversation, authorization, true);
        return new Projection(context.members().stream()
                .filter(member -> !member.profileId.equals(context.actor().id()) && groups.visible(context, member.profileId))
                .map(member -> context.eligible().get(member.profileId)).filter(this::visible).map(this::peer).toList());
    }

    private boolean visible(Profile profile) {
        var preferences = access.privacy(profile.id());
        return preferences.presenceVisible && !preferences.invisible;
    }

    private Peer peer(Profile profile) { return new Peer(profile.id(), profile.subject()); }

    record Peer(UUID communityProfileId, UUID subjectRoutingReference) {
        @Override public String toString() { return "PresencePeer[REDACTED]"; }
    }
    record Projection(List<Peer> peers) {
        @Override public String toString() { return "PresenceProjection[REDACTED]"; }
    }
}
