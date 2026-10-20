package com.mentalbridge.community.chat;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "community_chat_invitation")
class ChatInvitationEntity {
    @Id UUID id;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "inviter_membership_id", nullable = false) UUID inviterMembershipId;
    @Column(name = "inviter_profile_id", nullable = false) UUID inviterProfileId;
    @Column(name = "invitee_profile_id", nullable = false) UUID inviteeProfileId;
    @Column(nullable = false) String state = "PENDING";
    @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "decided_at") Instant decidedAt;
    @Column(name = "accepted_membership_id") UUID acceptedMembershipId;
    protected ChatInvitationEntity() {}
    ChatInvitationEntity(ChatMembershipEntity inviter, UUID invitee, Instant now) {
        id = UUID.randomUUID(); conversationId = inviter.conversationId; inviterMembershipId = inviter.id;
        inviterProfileId = inviter.profileId; inviteeProfileId = invitee; createdAt = now; expiresAt = now.plus(Duration.ofDays(7));
    }
    void finish(String state, Instant now) { this.state = state; decidedAt = now; version++; }
}
