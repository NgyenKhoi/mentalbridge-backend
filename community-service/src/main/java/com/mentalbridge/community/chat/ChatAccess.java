package com.mentalbridge.community.chat;

import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import com.mentalbridge.community.feed.CommunityChatProfiles.Profile;
import org.springframework.stereotype.Component;

@Component
class ChatAccess {
    private final CommunityChatProfiles profiles;
    private final ChatPrivacyRepository privacy;
    ChatAccess(CommunityChatProfiles profiles, ChatPrivacyRepository privacy) { this.profiles = profiles; this.privacy = privacy; }
    Profile actor(UUID subject) {
        var profile = profiles.ensure(subject);
        return lock(List.of(profile.id())).getFirst();
    }
    List<Profile> lock(List<UUID> ids) {
        var locked = profiles.lock(ids);
        if (locked.size() != ids.stream().distinct().count() || locked.stream().anyMatch(profile -> !profile.active())
                || privacy.restricted(ids)) throw ChatException.target();
        return locked;
    }
    List<Profile> pair(UUID actor, UUID other) {
        if (actor.equals(other)) throw ChatException.target();
        var locked = lock(List.of(actor, other));
        if (privacy.blocked(actor, other)) throw ChatException.target();
        return locked;
    }
    ChatPrivacyEntity privacy(UUID profile) { return privacy.findById(profile).orElseGet(() -> new ChatPrivacyEntity(profile)); }
    ChatModels.Peer peer(Profile profile) { return new ChatModels.Peer("AVAILABLE", profile.id(), profile.displayName()); }
}
