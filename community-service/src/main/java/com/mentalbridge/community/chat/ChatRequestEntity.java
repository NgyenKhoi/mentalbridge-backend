package com.mentalbridge.community.chat;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "community_chat_request")
class ChatRequestEntity {
    @Id UUID id;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "requester_profile_id", nullable = false) UUID requesterProfileId;
    @Column(name = "recipient_profile_id", nullable = false) UUID recipientProfileId;
    @Column(nullable = false) String state = "PENDING";
    @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "decided_at") Instant decidedAt;
    @Column(name = "decided_by_profile_id") UUID decidedByProfileId;
    protected ChatRequestEntity() {}
    ChatRequestEntity(UUID conversation, UUID requester, UUID recipient, Instant now) {
        id = UUID.randomUUID(); conversationId = conversation; requesterProfileId = requester;
        recipientProfileId = recipient; createdAt = now; expiresAt = now.plus(Duration.ofDays(7));
    }
    boolean pending(Instant now) { return state.equals("PENDING") && expiresAt.isAfter(now); }
    void decide(String state, Instant now) { this.state = state; decidedAt = now; version++; }
}
