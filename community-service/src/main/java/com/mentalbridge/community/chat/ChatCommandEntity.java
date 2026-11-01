package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "community_chat_command")
class ChatCommandEntity {
    @Id UUID id;
    @Column(name = "actor_profile_id", nullable = false) UUID actorProfileId;
    @Column(nullable = false) String operation;
    @Column(name = "idempotency_key", nullable = false) UUID idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false) String requestFingerprint;
    @Column(name = "result_kind", nullable = false) String resultKind;
    @Column(name = "target_id", nullable = false) UUID targetId;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    protected ChatCommandEntity() {}
    ChatCommandEntity(UUID actor, String operation, UUID key, String fingerprint, String kind, UUID target, UUID conversation, Instant now) {
        id = UUID.randomUUID(); actorProfileId = actor; this.operation = operation; idempotencyKey = key;
        requestFingerprint = fingerprint; resultKind = kind; targetId = target; conversationId = conversation; createdAt = now;
    }
}
