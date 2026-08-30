package com.mentalbridge.care.assessment;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assessment_submission")
class AssessmentSubmissionEntity {

	@Id
	@GeneratedValue
	@UuidGenerator
	private UUID id;

	private UUID userId;

	private UUID anonymousSessionId;

	private UUID definitionId;

	private String idempotencyKey;

	private String requestHash;

	private Instant submittedAt;

	private Instant retentionExpiresAt;

	private Instant voidedAt;

	protected AssessmentSubmissionEntity() {
	}

	static AssessmentSubmissionEntity authenticated(UUID userId, UUID definitionId, String idempotencyKey,
			String requestHash, Instant submittedAt) {
		var submission = new AssessmentSubmissionEntity();
		submission.userId = userId;
		submission.definitionId = definitionId;
		submission.idempotencyKey = idempotencyKey;
		submission.requestHash = requestHash;
		submission.submittedAt = submittedAt;
		return submission;
	}

	static AssessmentSubmissionEntity anonymous(UUID sessionId, UUID definitionId, String idempotencyKey,
			String requestHash, Instant submittedAt, Instant retentionExpiresAt) {
		var submission = new AssessmentSubmissionEntity();
		submission.anonymousSessionId = sessionId;
		submission.definitionId = definitionId;
		submission.idempotencyKey = idempotencyKey;
		submission.requestHash = requestHash;
		submission.submittedAt = submittedAt;
		submission.retentionExpiresAt = retentionExpiresAt;
		return submission;
	}

	UUID id() {
		return id;
	}

	UUID definitionId() {
		return definitionId;
	}

	String requestHash() {
		return requestHash;
	}

	Instant submittedAt() {
		return submittedAt;
	}

	Instant retentionExpiresAt() {
		return retentionExpiresAt;
	}

	Instant voidedAt() {
		return voidedAt;
	}
}
