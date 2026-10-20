package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "community_chat_message")
class ChatMessageEntity {
    @Id UUID id;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "sender_profile_id", nullable = false) UUID senderProfileId;
    @Column(name = "sender_membership_id", updatable = false) UUID senderMembershipId;
    @Column(name = "client_message_id", nullable = false) UUID clientMessageId;
    @Column(name = "server_sequence", nullable = false) long serverSequence;
    @Column(name = "content_ciphertext", columnDefinition = "text") String contentCiphertext;
    @Column(name = "content_fingerprint", nullable = false) String contentFingerprint;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "purged_at") Instant purgedAt;
    protected ChatMessageEntity() {}
    ChatMessageEntity(UUID id, UUID conversation, UUID sender, UUID clientId, long sequence,
            String ciphertext, String fingerprint, Instant now) {
        this(id, conversation, sender, null, clientId, sequence, ciphertext, fingerprint, now);
    }
    ChatMessageEntity(UUID id, UUID conversation, UUID sender, UUID membership, UUID clientId, long sequence,
            String ciphertext, String fingerprint, Instant now) {
        this.id = id; conversationId = conversation; senderProfileId = sender; clientMessageId = clientId;
        senderMembershipId = membership;
        serverSequence = sequence; contentCiphertext = ciphertext; contentFingerprint = fingerprint;
        createdAt = now; expiresAt = now.plus(java.time.Duration.ofDays(90));
    }
    boolean readable(Instant now) { return contentCiphertext != null && expiresAt.isAfter(now); }
}
