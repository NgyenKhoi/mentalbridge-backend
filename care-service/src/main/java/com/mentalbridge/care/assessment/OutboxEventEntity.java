package com.mentalbridge.care.assessment;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_event")
class OutboxEventEntity {

	@Id
	@GeneratedValue
	@UuidGenerator
	private UUID id;

	private String messageType;

	private String schemaVersion;

	private String aggregateType;

	private UUID aggregateId;

	private long aggregateVersion;

	private UUID correlationId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private JsonNode payload;

	private Instant occurredAt;

	private int attemptCount;

	private Instant createdAt;

	protected OutboxEventEntity() {
	}

	OutboxEventEntity(UUID aggregateId, UUID correlationId, JsonNode payload, Instant occurredAt) {
		this.messageType = "care.assessment.submitted";
		this.schemaVersion = "1.0";
		this.aggregateType = "ASSESSMENT";
		this.aggregateId = aggregateId;
		this.aggregateVersion = 0;
		this.correlationId = correlationId;
		this.payload = payload;
		this.occurredAt = occurredAt;
		this.attemptCount = 0;
		this.createdAt = occurredAt;
	}
}
