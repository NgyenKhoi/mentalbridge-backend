package com.mentalbridge.community.chat;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityChatVisibility {
    private final CommunityChatProfiles profiles;
    private final ChatPrivacyRepository privacy;
    private final ChatConversationRepository conversations;
    private final ChatRequestRepository requests;
    private final ChatFacts facts;
    private final Clock clock;
    public CommunityChatVisibility(CommunityChatProfiles profiles, ChatPrivacyRepository privacy,
            ChatConversationRepository conversations, ChatRequestRepository requests, ChatFacts facts, Clock clock) {
        this.profiles = profiles; this.privacy = privacy; this.conversations = conversations;
        this.requests = requests; this.facts = facts; this.clock = clock;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void changeBlock(UUID blocker, UUID blocked, boolean value, UUID correlation) {
        profiles.lock(List.of(blocker, blocked));
        int changed = value ? privacy.block(blocker, blocked, clock.instant()) : privacy.unblock(blocker, blocked);
        if (changed == 0) return;
        for (var profile : List.of(blocker, blocked)) {
            var preferences = privacy.findById(profile).orElseGet(() -> new ChatPrivacyEntity(profile));
            preferences.visibilityEpoch++;
            privacy.save(preferences);
        }
        for (var conversation : conversations.relationships(blocker)) {
            if (!conversation.other(blocker).equals(blocked)) continue;
            if (value) for (var request : requests.findByConversationIdAndState(conversation.id, "PENDING")) {
                request.decide("BLOCKED", clock.instant());
            }
            facts.invalidated(conversation, correlation);
        }
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void restrict(UUID profile, UUID correlation) {
        profiles.lock(List.of(profile));
        var preferences = privacy.findById(profile).orElseGet(() -> new ChatPrivacyEntity(profile));
        preferences.visibilityEpoch++;
        privacy.save(preferences);
        for (var conversation : conversations.relationships(profile)) {
            for (var request : requests.findByConversationIdAndState(conversation.id, "PENDING"))
                request.decide("BLOCKED", clock.instant());
            facts.invalidated(conversation, correlation);
        }
    }
}
