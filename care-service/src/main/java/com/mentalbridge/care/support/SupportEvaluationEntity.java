package com.mentalbridge.care.support;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_evaluation")
class SupportEvaluationEntity {

	@Id
	private UUID id;

	private UUID userId;

	private UUID phq9AssessmentId;

	private UUID gad7AssessmentId;

	private String policyVersion;

	@Enumerated(EnumType.STRING)
	private SupportTier supportTier;

	@Enumerated(EnumType.STRING)
	private SupportReasonCode primaryReasonCode;

	@Enumerated(EnumType.STRING)
	private SupportReasonCode secondaryReasonCode;

	private Instant evaluatedAt;

	protected SupportEvaluationEntity() {
	}

	SupportEvaluationEntity(UUID id, UUID userId, UUID phq9AssessmentId, UUID gad7AssessmentId,
			String policyVersion, SupportTier supportTier, SupportReasonCode primaryReasonCode,
			SupportReasonCode secondaryReasonCode, Instant evaluatedAt) {
		this.id = id;
		this.userId = userId;
		this.phq9AssessmentId = phq9AssessmentId;
		this.gad7AssessmentId = gad7AssessmentId;
		this.policyVersion = policyVersion;
		this.supportTier = supportTier;
		this.primaryReasonCode = primaryReasonCode;
		this.secondaryReasonCode = secondaryReasonCode;
		this.evaluatedAt = evaluatedAt;
	}

	UUID id() { return id; }
	UUID userId() { return userId; }
	UUID phq9AssessmentId() { return phq9AssessmentId; }
	UUID gad7AssessmentId() { return gad7AssessmentId; }
	String policyVersion() { return policyVersion; }
	SupportTier supportTier() { return supportTier; }
	SupportReasonCode primaryReasonCode() { return primaryReasonCode; }
	SupportReasonCode secondaryReasonCode() { return secondaryReasonCode; }
	Instant evaluatedAt() { return evaluatedAt; }
}
