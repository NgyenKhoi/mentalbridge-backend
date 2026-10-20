package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

final class ChatModels {
    private ChatModels() {}
    record Privacy(String directMessagePolicy, Boolean presenceVisible, Boolean invisible,
            Boolean typingVisible, Boolean readReceiptsVisible) {
        void validate() {
            if (!List.of("ANYONE", "APPROVAL_REQUIRED").contains(directMessagePolicy == null ? "" : directMessagePolicy)
                    || presenceVisible == null || invisible == null || typingVisible == null || readReceiptsVisible == null)
                throw ChatException.invalid();
        }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw ChatException.invalid(); }
    }
    record PrivacyView(String version, Privacy preferences) {}
    record StartDirect(UUID recipientCommunityProfileId) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw ChatException.invalid(); }
    }
    record RequestDecision(String decision) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw ChatException.invalid(); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DirectResult(String state, UUID conversationId, UUID requestId) {
        static DirectResult active(UUID id) { return new DirectResult("ACTIVE", id, null); }
        static DirectResult pending(UUID id) { return new DirectResult("PENDING", null, id); }
    }
    record Peer(String state, UUID communityProfileId, String displayName) {}
    record Request(UUID requestId, Peer requester, String state, Instant createdAt, Instant expiresAt, String version) {}
    record Page<T>(List<T> items, String nextCursor) {}
    record Conversation(UUID conversationId, String kind, String state, List<Peer> peers, int unreadCount, String version) {}
    static long version(String value) {
        if (value == null || !value.matches("\"[0-9]{1,19}\"")) throw ChatException.invalid();
        try { return Long.parseLong(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException exception) { throw ChatException.invalid(); }
    }
    static UUID uuid(String value) {
        try {
            if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw ChatException.invalid();
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) { throw ChatException.invalid(); }
    }
    static int limit(int value) { if (value < 1 || value > 50) throw ChatException.invalid(); return value; }
}
