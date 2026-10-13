package com.mentalbridge.consultation.specialist;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ConsultationAdminAuditOutbox {

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final Clock clock;

	public ConsultationAdminAuditOutbox(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.clock = clock;
	}

	public void record(String deduplicationKey, ConsultationAdminAuditEvent event) {
		String payloadJson;
		try {
			payloadJson = mapper.writeValueAsString(event);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize consultation admin audit event", e);
		}

		jdbc.sql("""
				insert into consultation_admin_audit_outbox (
				    id, deduplication_key, event_type, correlation_id, target_account_id,
				    payload, occurred_at, created_at
				) values (
				    :id, :deduplicationKey, :eventType, :correlationId, :targetAccountId,
				    :payload::jsonb, :occurredAt, :createdAt
				) on conflict (deduplication_key) do nothing
				""").param("id", event.eventId())
				.param("deduplicationKey", deduplicationKey)
				.param("eventType", event.eventType())
				.param("correlationId", event.correlationId())
				.param("targetAccountId", event.targetAccountId())
				.param("payload", payloadJson)
				.param("occurredAt", database(event.occurredAt()))
				.param("createdAt", database(clock.instant()))
				.update();
	}

	private OffsetDateTime database(java.time.Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}

