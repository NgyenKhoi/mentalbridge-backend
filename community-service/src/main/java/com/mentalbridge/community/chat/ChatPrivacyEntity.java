package com.mentalbridge.community.chat;

import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "community_chat_privacy")
class ChatPrivacyEntity {
    @Id @Column(name = "profile_id") UUID profileId;
    @Column(name = "direct_message_policy", nullable = false) String directMessagePolicy = "APPROVAL_REQUIRED";
    @Column(name = "presence_visible", nullable = false) boolean presenceVisible;
    @Column(nullable = false) boolean invisible;
    @Column(name = "typing_visible", nullable = false) boolean typingVisible;
    @Column(name = "read_receipts_visible", nullable = false) boolean readReceiptsVisible;
    @Column(nullable = false) long version;
    @Column(name = "visibility_epoch", nullable = false) long visibilityEpoch;
    protected ChatPrivacyEntity() {}
    ChatPrivacyEntity(UUID profileId) { this.profileId = profileId; }
    ChatModels.Privacy preferences() {
        return new ChatModels.Privacy(directMessagePolicy, presenceVisible, invisible, typingVisible, readReceiptsVisible);
    }
    boolean replace(ChatModels.Privacy value) {
        if (preferences().equals(value)) return false;
        directMessagePolicy = value.directMessagePolicy(); presenceVisible = value.presenceVisible();
        invisible = value.invisible(); typingVisible = value.typingVisible(); readReceiptsVisible = value.readReceiptsVisible();
        version++; visibilityEpoch++; return true;
    }
}
