package com.mentalbridge.community.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CommunityInteractionOutboxRelayPersistence {

	private final CommunityInteractionOutboxEventRepository events;
	private final CommunityInteractionRelayProperties properties;
	private final Clock clock;

	CommunityInteractionOutboxRelayPersistence(CommunityInteractionOutboxEventRepository events,
			CommunityInteractionRelayProperties properties, Clock clock) {
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	List<PendingInteractionEvent> claimBatch() {
		var now = clock.instant();
		var leaseUntil = now.plus(properties.sendTimeout()).plusSeconds(5);
		return events.findDueForUpdate(now, properties.batchSize()).stream().map(event -> {
			event.claim(leaseUntil);
			return PendingInteractionEvent.from(event);
		}).toList();
	}

	@Transactional
	void markPublished(UUID eventId) {
		events.findById(eventId).filter(event -> !event.isPublished())
				.ifPresent(event -> event.published(clock.instant()));
	}

	@Transactional
	void markFailed(UUID eventId, int attemptCount) {
		events.findById(eventId).filter(event -> !event.isPublished())
				.ifPresent(event -> event.retryAt(clock.instant().plus(retryDelay(attemptCount))));
	}

	private Duration retryDelay(int attemptCount) {
		var exponent = Math.min(Math.max(attemptCount - 1, 0), 20);
		var multiplier = 1L << exponent;
		var delay = properties.retryBase().multipliedBy(multiplier);
		return delay.compareTo(properties.retryMaximum()) > 0 ? properties.retryMaximum() : delay;
	}

	record PendingInteractionEvent(UUID id, UUID targetId, JsonNode eventPayload, Instant occurredAt,
			int attemptCount) {

		private static PendingInteractionEvent from(CommunityInteractionOutboxEventEntity event) {
			return new PendingInteractionEvent(event.id(), event.targetId(), event.eventPayload(), event.occurredAt(),
					event.attemptCount());
		}
	}
}
