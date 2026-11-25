package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "community_chat_membership")
class ChatMembershipEntity {
    @Id UUID id;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "profile_id", nullable = false) UUID profileId;
    @Column(nullable = false) String role;
    @Column(name = "joined_at", nullable = false) Instant joinedAt;
    @Column(name = "joined_after_sequence", nullable = false) long joinedAfterSequence;
    @Column(name = "departed_at") Instant departedAt;
    protected ChatMembershipEntity() {}
    ChatMembershipEntity(ChatConversationEntity group, UUID profile, String role, Instant now) {
        id = UUID.randomUUID(); conversationId = group.id; profileId = profile; this.role = role;
        joinedAt = now; joinedAfterSequence = group.lastSequence;
    }
    boolean manager() { return role.equals("OWNER") || role.equals("ADMIN"); }
}
