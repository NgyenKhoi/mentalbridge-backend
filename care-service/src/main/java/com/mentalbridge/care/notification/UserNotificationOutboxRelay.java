package com.mentalbridge.care.notification;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.notification.UserNotificationOutboxRelayPersistence.PendingFact;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.care.user-notification-relay", name = "enabled",
		havingValue = "true")
class UserNotificationOutboxRelay {

	private static final Logger LOGGER = LoggerFactory.getLogger(UserNotificationOutboxRelay.class);

	private final UserNotificationOutboxRelayPersistence persistence;
	private final UserNotificationRelayProperties properties;
	private final KafkaTemplate<String, String> kafka;
	private final ObjectMapper json;

	UserNotificationOutboxRelay(UserNotificationOutboxRelayPersistence persistence,
			UserNotificationRelayProperties properties, KafkaTemplate<String, String> kafka,
			ObjectMapper json) {
		this.persistence = persistence;
		this.properties = properties;
		this.kafka = kafka;
		this.json = json;
	}

	@Scheduled(fixedDelayString = "${mentalbridge.care.user-notification-relay.interval}")
	void publishDue() {
		for (var event : persistence.claimBatch()) publish(event);
	}

	private void publish(PendingFact event) {
		try {
			var envelope = new Envelope(event.id(), event.messageType(), event.occurredAt(),
					"care-service", event.schemaVersion(), event.correlationId(), event.aggregateId(),
					event.aggregateVersion(), event.payload());
			kafka.send(properties.topic(), event.aggregateId().toString(), json.writeValueAsString(envelope))
					.get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
			persistence.markPublished(event.id());
		}
		catch (Exception exception) {
			persistence.markFailed(event.id(), event.attemptCount());
			LOGGER.warn("Care user notification relay failed messageId={} attempt={}", event.id(),
					event.attemptCount());
		}
	}

	private record Envelope(java.util.UUID messageId, String messageType, java.time.Instant occurredAt,
			String producer, String schemaVersion, java.util.UUID correlationId,
			java.util.UUID aggregateId, long aggregateVersion,
			com.fasterxml.jackson.databind.JsonNode payload) {
	}
}
