package com.mentalbridge.care.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
class UserNotificationOutboxRelayPersistence {

	private final JdbcTemplate jdbc;
	private final UserNotificationRelayProperties properties;
	private final ObjectMapper json;
	private final Clock clock;

	UserNotificationOutboxRelayPersistence(JdbcTemplate jdbc, UserNotificationRelayProperties properties,
			ObjectMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.json = json;
		this.clock = clock;
	}

	@Transactional
	List<PendingFact> claimBatch() {
		var leaseUntil = clock.instant().plus(properties.sendTimeout()).plusSeconds(5);
		return jdbc.query("""
				with due as (
					select id from outbox_event
					where message_type = 'care.user-notification.fact'
					  and published_at is null
					  and coalesce(next_attempt_at, occurred_at) <= now()
					order by coalesce(next_attempt_at, occurred_at), occurred_at, id
					for update skip locked
					limit ?
				)
				update outbox_event event
				set attempt_count = event.attempt_count + 1, next_attempt_at = ?
				from due where event.id = due.id
				returning event.id, event.message_type, event.schema_version, event.aggregate_id,
					event.aggregate_version, event.correlation_id, event.payload::text,
					event.occurred_at, event.attempt_count
				""", (result, row) -> new PendingFact(
				(UUID) result.getObject("id"),
				result.getString("message_type"),
				result.getString("schema_version"),
				(UUID) result.getObject("aggregate_id"),
				result.getLong("aggregate_version"),
				(UUID) result.getObject("correlation_id"),
				parse(result.getString("payload")),
				result.getTimestamp("occurred_at").toInstant(),
				result.getInt("attempt_count")),
			properties.batchSize(), Timestamp.from(leaseUntil));
	}

	@Transactional
	void markPublished(UUID eventId) {
		jdbc.update("""
				update outbox_event set published_at = ?, next_attempt_at = null
				where id = ? and published_at is null
				""", Timestamp.from(clock.instant()), eventId);
	}

	@Transactional
	void markFailed(UUID eventId, int attemptCount) {
		jdbc.update("""
				update outbox_event set next_attempt_at = ?
				where id = ? and published_at is null
				""", Timestamp.from(clock.instant().plus(retryDelay(attemptCount))), eventId);
	}

	private Duration retryDelay(int attemptCount) {
		var exponent = Math.min(Math.max(attemptCount - 1, 0), 20);
		var delay = properties.retryBase().multipliedBy(1L << exponent);
		return delay.compareTo(properties.retryMaximum()) > 0 ? properties.retryMaximum() : delay;
	}

	private JsonNode parse(String payload) {
		try {
			return json.readTree(payload);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Stored Care notification fact is not valid JSON", exception);
		}
	}

	record PendingFact(UUID id, String messageType, String schemaVersion, UUID aggregateId,
			long aggregateVersion, UUID correlationId, JsonNode payload, Instant occurredAt,
			int attemptCount) {
	}
}
