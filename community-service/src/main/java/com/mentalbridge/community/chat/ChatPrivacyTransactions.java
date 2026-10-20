package com.mentalbridge.community.chat;

import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatPrivacyTransactions {
    private final CommunityChatProfiles profiles;
    private final ChatAccess access;
    private final ChatPrivacyRepository privacy;
    private final ChatConversationRepository conversations;
    private final ChatFacts facts;
    ChatPrivacyTransactions(CommunityChatProfiles profiles, ChatAccess access, ChatPrivacyRepository privacy,
            ChatConversationRepository conversations, ChatFacts facts) {
        this.profiles = profiles; this.access = access; this.privacy = privacy;
        this.conversations = conversations; this.facts = facts;
    }
    @Transactional
    ChatModels.PrivacyView get(UUID subject) {
        var existing = profiles.bySubject(subject);
        if (existing.isEmpty()) return new ChatModels.PrivacyView("0", new ChatPrivacyEntity(null).preferences());
        var actor = access.lock(java.util.List.of(existing.orElseThrow().id())).getFirst();
        var value = access.privacy(actor.id());
        return new ChatModels.PrivacyView(Long.toString(value.version), value.preferences());
    }
    @Transactional
    ChatModels.PrivacyView replace(UUID subject, long expected, ChatModels.Privacy input, UUID correlation) {
        input.validate();
        var actor = access.actor(subject);
        var value = access.privacy(actor.id());
        if (value.version != expected) throw ChatException.version();
        if (value.replace(input)) {
            privacy.save(value);
            for (var conversation : conversations.relationships(actor.id())) facts.invalidated(conversation, correlation);
        }
        return new ChatModels.PrivacyView(Long.toString(value.version), value.preferences());
    }
}
