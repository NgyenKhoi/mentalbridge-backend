package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "community_chat_conversation")
class ChatConversationEntity {
    @Id UUID id;
    @Column(name = "first_profile_id") UUID firstProfileId;
    @Column(name = "second_profile_id") UUID secondProfileId;
    @Column(nullable = false) String kind = "DIRECT";
    @Column(name = "name_ciphertext") String nameCiphertext;
    @Column(name = "member_limit") Integer memberLimit;
    @Column(nullable = false) String state = "DORMANT";
    @Column(name = "management_version", nullable = false) long managementVersion;
    @Column(name = "event_version", nullable = false) long eventVersion;
    @Column(name = "last_sequence", nullable = false) long lastSequence;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "activated_at") Instant activatedAt;
    protected ChatConversationEntity() {}
    ChatConversationEntity(UUID first, UUID second, Instant now) {
        id = UUID.randomUUID(); firstProfileId = first; secondProfileId = second; createdAt = now;
    }
    void activate(Instant now) {
        if (!state.equals("ACTIVE")) { state = "ACTIVE"; managementVersion++; activatedAt = now; }
    }
    UUID other(UUID viewer) {
        if (!kind.equals("DIRECT")) throw ChatException.target();
        if (firstProfileId.equals(viewer)) return secondProfileId;
        if (secondProfileId.equals(viewer)) return firstProfileId;
        throw ChatException.target();
    }
}
