package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "community_friendship")
class FriendshipEntity {
    @Id UUID id;
    @Column(name = "first_profile_id", nullable = false) UUID firstProfileId;
    @Column(name = "second_profile_id", nullable = false) UUID secondProfileId;
    @Column(name = "requester_profile_id", nullable = false) UUID requesterProfileId;
    @Column(nullable = false, length = 16) String state;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "accepted_at") Instant acceptedAt;

    protected FriendshipEntity() {}

    FriendshipEntity(UUID first, UUID second, UUID requester, Instant now) {
        id = UUID.randomUUID(); firstProfileId = first; secondProfileId = second;
        requesterProfileId = requester; state = "PENDING"; createdAt = now;
    }

    UUID other(UUID actor) {
        if (actor.equals(firstProfileId)) return secondProfileId;
        if (actor.equals(secondProfileId)) return firstProfileId;
        throw ChatException.target();
    }

    String viewerState(UUID actor) {
        other(actor);
        return state.equals("ACCEPTED") ? "FRIENDS" : requesterProfileId.equals(actor) ? "OUTGOING" : "INCOMING";
    }
}
