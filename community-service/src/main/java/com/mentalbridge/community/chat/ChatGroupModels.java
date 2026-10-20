package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.*;

final class ChatGroupModels {
    private ChatGroupModels() {}
    record Create(String name) { @Override public String toString() { return "CreateGroup[REDACTED]"; } }
    record Invite(UUID inviteeCommunityProfileId) {}
    record Decision(String decision) {}
    record Role(String role) {}
    record Transfer(UUID newOwnerCommunityProfileId) {}
    record Group(UUID conversationId, String kind, String name, String state, int memberLimit, String version) {
        @Override public String toString() { return "ChatGroup[REDACTED]"; }
    }
    record Invitation(UUID invitationId, UUID conversationId, String groupName, ChatModels.Peer inviter,
            String state, Instant expiresAt, String version) { @Override public String toString() { return "ChatInvitation[REDACTED]"; } }
    record Member(ChatModels.Peer peer, String role, Instant joinedAt) { @Override public String toString() { return "ChatMember[REDACTED]"; } }
    record Conversation(UUID conversationId, String kind, String state, String groupName, String viewerRole,
            List<ChatModels.Peer> peers, int unreadCount, String version) { @Override public String toString() { return "ChatGroupConversation[REDACTED]"; } }
    record Versioned<T>(T body, long version) {}
}
