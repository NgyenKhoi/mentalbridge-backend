package com.mentalbridge.consultation.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.consultation.appointment-notifications", name = "relay-enabled", havingValue = "true")
public class AppointmentOutboxRelay {

	private static final Logger LOGGER = LoggerFactory.getLogger(AppointmentOutboxRelay.class);
	private final JdbcClient jdbc;
	private final AppointmentNotificationProperties properties;
	private final KafkaTemplate<String, String> kafka;
	private final ObjectMapper mapper;
	private final Clock clock;

	public AppointmentOutboxRelay(JdbcClient jdbc, AppointmentNotificationProperties properties,
			KafkaTemplate<String, String> kafka, ObjectMapper mapper, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.kafka = kafka;
		this.mapper = mapper;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${mentalbridge.consultation.appointment-notifications.relay-interval}")
	public void publishDue() {
		for (var event : claim()) publish(event);
	}

	@Transactional
	List<PendingEvent> claim() {
		var now = clock.instant();
		var events = jdbc.sql("""
				select id, appointment_id, appointment_version, correlation_id,
				       payload::text, occurred_at, attempt_count
				from appointment_outbox_event
				where published_at is null and coalesce(next_attempt_at, occurred_at)<=:now
				order by coalesce(next_attempt_at, occurred_at), occurred_at, id
				for update skip locked limit :limit
				""").param("now", database(now)).param("limit", properties.batchSize())
				.query((row, ignored) -> new PendingEvent(row.getObject("id", UUID.class),
						row.getObject("appointment_id", UUID.class), row.getLong("appointment_version"),
						row.getObject("correlation_id", UUID.class), row.getString("payload"),
						row.getTimestamp("occurred_at").toInstant(), row.getInt("attempt_count") + 1)).list();
		for (var event : events) {
			jdbc.sql("update appointment_outbox_event set attempt_count=:attempt, next_attempt_at=:lease where id=:id")
					.param("attempt", event.attempt()).param("lease", database(now.plus(properties.sendTimeout()).plusSeconds(5)))
					.param("id", event.id()).update();
		}
		return events;
	}

	private void publish(PendingEvent event) {
		try {
			var value = mapper.writeValueAsString(new Envelope(event.id(), "consultation.appointment.status-changed",
					event.occurredAt(), "consultation-service", "1.0", event.correlationId(), event.appointmentId(),
					event.version(), mapper.readTree(event.payload())));
			kafka.send(properties.topic(), event.appointmentId().toString(), value)
					.get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
			jdbc.sql("update appointment_outbox_event set published_at=:now, next_attempt_at=null where id=:id and published_at is null")
					.param("now", database(clock.instant())).param("id", event.id()).update();
		}
		catch (Exception exception) {
			var delay = retryDelay(event.attempt());
			jdbc.sql("update appointment_outbox_event set next_attempt_at=:next where id=:id and published_at is null")
					.param("next", database(clock.instant().plus(delay))).param("id", event.id()).update();
			LOGGER.warn("Appointment outbox publish failed messageId={} attempt={}", event.id(), event.attempt());
		}
	}

	private Duration retryDelay(int attempt) {
		var delay = properties.retryBase().multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 20));
		return delay.compareTo(properties.retryMaximum()) > 0 ? properties.retryMaximum() : delay;
	}

	private OffsetDateTime database(java.time.Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	record PendingEvent(UUID id, UUID appointmentId, long version, UUID correlationId, String payload,
			java.time.Instant occurredAt, int attempt) {
	}

	record Envelope(UUID messageId, String messageType, java.time.Instant occurredAt, String producer,
			String schemaVersion, UUID correlationId, UUID aggregateId, long aggregateVersion, JsonNode payload) {
	}
}
