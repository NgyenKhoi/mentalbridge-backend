package com.mentalbridge.community.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.community.chat-relay", name = "enabled", havingValue = "true")
class ChatOutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(ChatOutboxRelay.class);
    private final ChatRelayPersistence persistence;
    private final ChatKafkaSink sink;
    private final ChatRelayProperties properties;
    ChatOutboxRelay(ChatRelayPersistence persistence, ChatKafkaSink sink, ChatRelayProperties properties) {
        this.persistence = persistence; this.sink = sink; this.properties = properties;
    }
    @Scheduled(fixedDelayString = "${mentalbridge.community.chat-relay.interval:PT5S}")
    void publishDue() {
        for (int index = 0; index < properties.batchSize(); index++) {
            var pending = persistence.claim();
            if (pending.isEmpty()) return;
            var fact = pending.orElseThrow();
            try {
                sink.publish(fact);
                persistence.published(fact);
            } catch (Exception exception) {
                persistence.failed(fact);
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.warn("Community chat relay deferred eventId={} attempt={}", fact.id(), fact.attempt());
                return;
            }
        }
    }
}
