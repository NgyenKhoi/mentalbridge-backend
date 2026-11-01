package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "community_chat_outbox")
class ChatOutboxEntity {
    @Id UUID id;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "deduplication_key", nullable = false) String deduplicationKey;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "event_payload", nullable = false, columnDefinition = "jsonb") JsonNode eventPayload;
    @Column(name = "occurred_at", nullable = false) Instant occurredAt;
    @Column(name = "published_at") Instant publishedAt;
    @Column(name = "attempt_count", nullable = false) int attemptCount;
    @Column(name = "next_attempt_at") Instant nextAttemptAt;
    protected ChatOutboxEntity() {}
    ChatOutboxEntity(UUID id, UUID conversation, String deduplicationKey, JsonNode payload, Instant now) {
        this.id = id; conversationId = conversation; this.deduplicationKey = deduplicationKey;
        eventPayload = payload; occurredAt = now;
    }
}
