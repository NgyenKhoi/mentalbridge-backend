package com.mentalbridge.community.chat;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ChatFacts {
    private final ChatOutboxRepository events;
    private final ObjectMapper mapper;
    private final Clock clock;
    ChatFacts(ChatOutboxRepository events, ObjectMapper mapper, Clock clock) {
        this.events = events; this.mapper = mapper; this.clock = clock;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    void publish(ChatConversationEntity conversation, String kind, UUID target, UUID actor, UUID recipient, UUID correlation, Long sequence) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var eventId = UUID.randomUUID();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("kind", kind); payload.put("conversationId", conversation.id);
        if (target != null) payload.put("targetId", target);
        if (recipient != null) payload.put("recipientRoutingReference", recipient);
        if (actor != null) payload.put("actorCommunityProfileId", actor);
        if (sequence != null) payload.put("sequence", sequence.toString());
        var fact = Map.of("messageId", eventId, "messageType", "community.chat.fact", "schemaVersion", "1.0",
                "producer", "community-service", "occurredAt", now.toString(), "correlationId", correlation,
                "aggregateId", conversation.id, "aggregateVersion", Long.toString(conversation.eventVersion), "payload", payload);
        var key = conversation.id + ":" + conversation.eventVersion + ":" + kind + ":" + target + ":" + recipient;
        events.save(new ChatOutboxEntity(eventId, conversation.id, key, mapper.valueToTree(fact), now));
    }
    void invalidated(ChatConversationEntity conversation, UUID correlation) {
        conversation.eventVersion++;
        publish(conversation, "AUTHORIZATION_CHANGED", null, null, null, correlation, null);
    }
}
