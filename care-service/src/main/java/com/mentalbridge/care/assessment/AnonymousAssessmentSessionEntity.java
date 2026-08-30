package com.mentalbridge.care.assessment;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "anonymous_assessment_session")
class AnonymousAssessmentSessionEntity {

	@Id
	@GeneratedValue
	@UuidGenerator
	private UUID id;

	private String tokenHash;

	private Instant expiresAt;

	private Instant closedAt;

	private Instant createdAt;

	protected AnonymousAssessmentSessionEntity() {
	}

	AnonymousAssessmentSessionEntity(String tokenHash, Instant createdAt, Instant expiresAt) {
		this.tokenHash = tokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	UUID id() {
		return id;
	}

	String tokenHash() {
		return tokenHash;
	}

	Instant expiresAt() {
		return expiresAt;
	}

	boolean unavailableAt(Instant now) {
		return closedAt != null || !expiresAt.isAfter(now);
	}
}
