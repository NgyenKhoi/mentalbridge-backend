package com.mentalbridge.identity.registration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.configuration.OutboxRelayProperties;

@Service
public class OutboxRelayPersistence {

	private final OutboxEventRepository events;
	private final OutboxRelayProperties properties;
	private final Clock clock;

	public OutboxRelayPersistence(OutboxEventRepository events, OutboxRelayProperties properties, Clock clock) {
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public List<PendingOutboxEvent> claimBatch() {
		var now = clock.instant();
		var leaseUntil = now.plus(properties.sendTimeout()).plusSeconds(5);
		return events.findDueForUpdate(now, properties.batchSize()).stream().map(event -> {
			event.claim(leaseUntil);
			return PendingOutboxEvent.from(event);
		}).toList();
	}

	@Transactional
	public void markPublished(UUID eventId) {
		events.findById(eventId).filter(event -> !event.isPublished())
				.ifPresent(event -> event.published(clock.instant()));
	}

	@Transactional
	public void markFailed(UUID eventId, int attemptCount) {
		events.findById(eventId).filter(event -> !event.isPublished())
				.ifPresent(event -> event.retryAt(clock.instant().plus(retryDelay(attemptCount))));
	}

	private Duration retryDelay(int attemptCount) {
		var exponent = Math.min(Math.max(attemptCount - 1, 0), 20);
		var multiplier = 1L << exponent;
		var delay = properties.retryBase().multipliedBy(multiplier);
		return delay.compareTo(properties.retryMaximum()) > 0 ? properties.retryMaximum() : delay;
	}

	public record PendingOutboxEvent(UUID id, String messageType, String schemaVersion, UUID aggregateId,
			long aggregateVersion, UUID correlationId, Map<String, String> payload, Instant occurredAt,
			int attemptCount) {

		private static PendingOutboxEvent from(OutboxEventEntity event) {
			return new PendingOutboxEvent(event.id(), event.messageType(), event.schemaVersion(), event.aggregateId(),
					event.aggregateVersion(), event.correlationId(), event.payload(), event.occurredAt(),
					event.attemptCount());
		}
	}
}
