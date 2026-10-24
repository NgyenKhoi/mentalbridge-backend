package com.mentalbridge.community.chat;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "community_chat_read_state")
class ChatReadStateEntity {
    @EmbeddedId ReadId id;
    @Column(name = "last_read_sequence", nullable = false) long lastReadSequence;
    @Column(name = "cleared_through_sequence", nullable = false) long clearedThroughSequence;
    @Column(name = "cleared_at") Instant clearedAt;
    protected ChatReadStateEntity() {}
    ChatReadStateEntity(UUID conversation, UUID viewer) { id = new ReadId(conversation, viewer); }
    @Embeddable
    static class ReadId implements Serializable {
        @Column(name = "conversation_id") UUID conversationId;
        @Column(name = "profile_id") UUID profileId;
        protected ReadId() {}
        ReadId(UUID conversation, UUID viewer) { conversationId = conversation; profileId = viewer; }
        @Override public boolean equals(Object value) {
            return this == value || value instanceof ReadId other
                    && Objects.equals(conversationId, other.conversationId) && Objects.equals(profileId, other.profileId);
        }
        @Override public int hashCode() { return Objects.hash(conversationId, profileId); }
    }
}
