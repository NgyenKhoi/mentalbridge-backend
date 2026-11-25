package com.mentalbridge.community.notification;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "community_interaction_outbox")
class CommunityInteractionOutboxEventEntity {

	@Id
	private UUID id;

	@Column(name = "deduplication_key", nullable = false, unique = true, length = 200)
	private String deduplicationKey;

	@Column(name = "target_id", nullable = false)
	private UUID targetId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "event_payload", nullable = false, columnDefinition = "jsonb")
	private JsonNode eventPayload;

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

	protected CommunityInteractionOutboxEventEntity() {
	}

	CommunityInteractionOutboxEventEntity(String deduplicationKey, CommunityInteractionFact fact,
			JsonNode eventPayload) {
		this.id = fact.eventId();
		this.deduplicationKey = deduplicationKey;
		this.targetId = fact.targetId();
		this.eventPayload = eventPayload.deepCopy();
		this.occurredAt = fact.occurredAt();
		this.createdAt = fact.occurredAt();
	}

	void claim(Instant nextAttemptAt) {
		attemptCount++;
		this.nextAttemptAt = nextAttemptAt;
	}

	void published(Instant publishedAt) {
		this.publishedAt = publishedAt;
		this.nextAttemptAt = null;
	}

	void retryAt(Instant nextAttemptAt) {
		this.nextAttemptAt = nextAttemptAt;
	}

	UUID id() {
		return id;
	}

	UUID targetId() {
		return targetId;
	}

	JsonNode eventPayload() {
		return eventPayload.deepCopy();
	}

	Instant occurredAt() {
		return occurredAt;
	}

	int attemptCount() {
		return attemptCount;
	}

	boolean isPublished() {
		return publishedAt != null;
	}
}
