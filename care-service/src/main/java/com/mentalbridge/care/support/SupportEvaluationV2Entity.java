package com.mentalbridge.care.support;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_evaluation_v2")
class SupportEvaluationV2Entity {

	@Id
	private UUID id;

	private UUID userId;

	private UUID phq9AssessmentId;

	private UUID gad7AssessmentId;

	private String policyVersion;

	private Instant evaluatedAt;

	protected SupportEvaluationV2Entity() {
	}

	SupportEvaluationV2Entity(UUID id, UUID userId, UUID phq9AssessmentId, UUID gad7AssessmentId,
			String policyVersion, Instant evaluatedAt) {
		this.id = id;
		this.userId = userId;
		this.phq9AssessmentId = phq9AssessmentId;
		this.gad7AssessmentId = gad7AssessmentId;
		this.policyVersion = policyVersion;
		this.evaluatedAt = evaluatedAt;
	}

	UUID id() { return id; }
	UUID userId() { return userId; }
	UUID phq9AssessmentId() { return phq9AssessmentId; }
	UUID gad7AssessmentId() { return gad7AssessmentId; }
	String policyVersion() { return policyVersion; }
	Instant evaluatedAt() { return evaluatedAt; }
}
