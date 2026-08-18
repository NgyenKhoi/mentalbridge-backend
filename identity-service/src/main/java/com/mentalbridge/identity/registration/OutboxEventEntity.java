package com.mentalbridge.identity.registration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {

	@Id
	private UUID id;

	@Column(name = "message_type", nullable = false, length = 120)
	private String messageType;

	@Column(name = "schema_version", nullable = false, length = 24)
	private String schemaVersion;

	@Column(name = "aggregate_type", nullable = false, length = 64)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false)
	private UUID aggregateId;

	@Column(name = "aggregate_version", nullable = false)
	private long aggregateVersion;

	@Column(name = "correlation_id", nullable = false)
	private UUID correlationId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, String> payload;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected OutboxEventEntity() {
	}

	public OutboxEventEntity(String messageType, UUID aggregateId, long aggregateVersion, UUID correlationId,
			Map<String, String> payload, Instant occurredAt) {
		this.id = UUID.randomUUID();
		this.messageType = messageType;
		this.schemaVersion = "1.0";
		this.aggregateType = "ACCOUNT";
		this.aggregateId = aggregateId;
		this.aggregateVersion = aggregateVersion;
		this.correlationId = correlationId;
		this.payload = Map.copyOf(payload);
		this.occurredAt = occurredAt;
		this.createdAt = occurredAt;
	}

}
