package com.mentalbridge.community.chat;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatRelayPersistence {
    private final ChatOutboxRepository events;
    private final Clock clock;
    ChatRelayPersistence(ChatOutboxRepository events, Clock clock) { this.events = events; this.clock = clock; }

    @Transactional
    Optional<PendingFact> claim() {
        return events.due(clock.instant(), 1).stream().findFirst().map(event -> {
            event.attemptCount++;
            event.nextAttemptAt = clock.instant().plusSeconds(10);
            return new PendingFact(event.id, event.conversationId, event.eventPayload.deepCopy(), event.attemptCount);
        });
    }

    @Transactional
    void published(PendingFact fact) {
        events.lock(fact.id()).filter(event -> event.publishedAt == null && event.attemptCount == fact.attempt())
                .ifPresent(event -> { event.publishedAt = clock.instant(); event.nextAttemptAt = null; });
    }

    @Transactional
    void failed(PendingFact fact) {
        events.lock(fact.id()).filter(event -> event.publishedAt == null && event.attemptCount == fact.attempt())
                .ifPresent(event -> event.nextAttemptAt = clock.instant().plusSeconds(Math.min(30, 1L << Math.min(Math.max(fact.attempt() - 1, 0), 5))));
    }

    record PendingFact(UUID id, UUID conversationId, JsonNode payload, int attempt) {}
}
