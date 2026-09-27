package com.mentalbridge.identity.registration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.configuration.OutboxRelayProperties;
import com.mentalbridge.identity.registration.OutboxRelayPersistence.PendingOutboxEvent;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.identity.outbox-relay", name = "enabled", havingValue = "true")
public class OutboxRelay {

	private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboxRelayPersistence persistence;
	private final OutboxRelayProperties properties;
	private final KafkaTemplate<String, String> kafka;
	private final ObjectMapper objectMapper;

	public OutboxRelay(OutboxRelayPersistence persistence, OutboxRelayProperties properties,
			KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
		this.persistence = persistence;
		this.properties = properties;
		this.kafka = kafka;
		this.objectMapper = objectMapper;
	}

	@Scheduled(fixedDelayString = "${mentalbridge.identity.outbox-relay.interval}")
	public void publishDue() {
		for (var event : persistence.claimBatch()) {
			publish(event);
		}
	}

	private void publish(PendingOutboxEvent event) {
		try {
			kafka.send(properties.topic(), event.aggregateId().toString(), serialize(event))
					.get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
			persistence.markPublished(event.id());
		}
		catch (Exception exception) {
			persistence.markFailed(event.id(), event.attemptCount());
			LOGGER.warn("Identity outbox publish failed messageId={} messageType={} attempt={}", event.id(),
					event.messageType(), event.attemptCount());
		}
	}

	private String serialize(PendingOutboxEvent event) throws JsonProcessingException {
		return objectMapper.writeValueAsString(new AccountLifecycleEnvelope(event.id(), event.messageType(),
				event.occurredAt(), "identity-service", event.schemaVersion(), event.correlationId(), event.aggregateId(),
				event.aggregateVersion(), event.payload()));
	}

	private record AccountLifecycleEnvelope(UUID messageId, String messageType, Instant occurredAt, String producer,
			String schemaVersion, UUID correlationId, UUID aggregateId, long aggregateVersion,
			Map<String, String> payload) {
	}
}
