package com.mentalbridge.consultation.specialist;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.consultation.admin-audit", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
public class ConsultationAdminAuditRelay {

	private static final Logger LOGGER = LoggerFactory.getLogger(ConsultationAdminAuditRelay.class);

	private final JdbcClient jdbc;
	private final ObjectProvider<KafkaTemplate<String, String>> kafkaProvider;
	private final Clock clock;
	private final String topic;
	private final Duration sendTimeout;
	private final int batchSize;

	public ConsultationAdminAuditRelay(
			JdbcClient jdbc,
			ObjectProvider<KafkaTemplate<String, String>> kafkaProvider,
			Clock clock,
			@Value("${mentalbridge.consultation.admin-audit.topic:mentalbridge.admin.audit-event.v1}") String topic,
			@Value("${mentalbridge.consultation.admin-audit.send-timeout:PT5S}") Duration sendTimeout,
			@Value("${mentalbridge.consultation.admin-audit.batch-size:50}") int batchSize) {
		this.jdbc = jdbc;
		this.kafkaProvider = kafkaProvider;
		this.clock = clock;
		this.topic = topic;
		this.sendTimeout = sendTimeout;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${mentalbridge.consultation.admin-audit.relay-interval:PT5S}")
	public void publishDue() {
		for (var event : claim()) {
			publish(event);
		}
	}

	@Transactional
	List<PendingOutboxEvent> claim() {
		var now = clock.instant();
		var leaseUntil = now.plus(sendTimeout).plusSeconds(5);
		var events = jdbc.sql("""
				select id, target_account_id, payload::text, occurred_at, attempt_count
				from consultation_admin_audit_outbox
				where published_at is null and coalesce(next_attempt_at, occurred_at) <= :now
				order by coalesce(next_attempt_at, occurred_at), occurred_at, id
				for update skip locked limit :limit
				""").param("now", database(now)).param("limit", batchSize)
				.query((row, ignored) -> new PendingOutboxEvent(
						row.getObject("id", UUID.class),
						row.getObject("target_account_id", UUID.class),
						row.getString("payload"),
						row.getTimestamp("occurred_at").toInstant(),
						row.getInt("attempt_count") + 1
				)).list();

		for (var event : events) {
			jdbc.sql("update consultation_admin_audit_outbox set attempt_count = :attempt, next_attempt_at = :lease where id = :id")
					.param("attempt", event.attempt())
					.param("lease", database(leaseUntil))
					.param("id", event.id())
					.update();
		}
		return events;
	}

	private void publish(PendingOutboxEvent event) {
		var kafka = kafkaProvider.getIfAvailable();
		if (kafka == null) {
			LOGGER.debug("Kafka template unavailable for consultation admin audit relay; will retry: eventId={}", event.id());
			return;
		}

		try {
			String messageKey = event.targetAccountId() != null ? event.targetAccountId().toString() : event.id().toString();
			kafka.send(topic, messageKey, event.payload()).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
			jdbc.sql("update consultation_admin_audit_outbox set published_at = :now, next_attempt_at = null where id = :id and published_at is null")
					.param("now", database(clock.instant()))
					.param("id", event.id())
					.update();
			LOGGER.info("Published consultation admin audit event from outbox: eventId={}", event.id());
		}
		catch (Exception exception) {
			var delay = Duration.ofSeconds(Math.min((long) Math.pow(2, Math.min(event.attempt(), 6)), 300));
			jdbc.sql("update consultation_admin_audit_outbox set next_attempt_at = :next where id = :id and published_at is null")
					.param("next", database(clock.instant().plus(delay)))
					.param("id", event.id())
					.update();
			LOGGER.warn("Failed to publish consultation admin audit event eventId={}, attempt={}, error={}", event.id(), event.attempt(), exception.getMessage());
		}
	}

	private OffsetDateTime database(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	record PendingOutboxEvent(UUID id, UUID targetAccountId, String payload, Instant occurredAt, int attempt) {
	}
}
