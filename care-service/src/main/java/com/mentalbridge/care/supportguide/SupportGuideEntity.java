package com.mentalbridge.care.supportguide;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_guide")
class SupportGuideEntity {

	@Id
	private UUID id;
	private UUID userId;
	private UUID supportEvaluationId;
	private String guidePolicyVersion;
	private Instant generatedAt;
	private String explanationCode;
	@Column(length = 2048)
	private String explanationText;
	private String safetyStatus;
	private String safetyReasonCode;
	private String safetyPolicyVersion;
	private String safetyGuidanceCode;
	@Column(length = 2048)
	private String safetyGuidance;
	private String resourceStatus;
	private String resourcePolicyVersion;
	private Instant resourcesResolvedAt;
	private String phrasingStatus;

	protected SupportGuideEntity() { }

	SupportGuideEntity(UUID id, UUID userId, UUID supportEvaluationId, String guidePolicyVersion,
			Instant generatedAt, String explanationCode, String explanationText, String safetyStatus,
			String safetyReasonCode, String safetyPolicyVersion, String safetyGuidanceCode,
			String safetyGuidance, String resourceStatus, String resourcePolicyVersion,
			Instant resourcesResolvedAt, String phrasingStatus) {
		this.id = id;
		this.userId = userId;
		this.supportEvaluationId = supportEvaluationId;
		this.guidePolicyVersion = guidePolicyVersion;
		this.generatedAt = generatedAt;
		this.explanationCode = explanationCode;
		this.explanationText = explanationText;
		this.safetyStatus = safetyStatus;
		this.safetyReasonCode = safetyReasonCode;
		this.safetyPolicyVersion = safetyPolicyVersion;
		this.safetyGuidanceCode = safetyGuidanceCode;
		this.safetyGuidance = safetyGuidance;
		this.resourceStatus = resourceStatus;
		this.resourcePolicyVersion = resourcePolicyVersion;
		this.resourcesResolvedAt = resourcesResolvedAt;
		this.phrasingStatus = phrasingStatus;
	}

	UUID id() { return id; }
	UUID userId() { return userId; }
	UUID supportEvaluationId() { return supportEvaluationId; }
	String guidePolicyVersion() { return guidePolicyVersion; }
	Instant generatedAt() { return generatedAt; }
	String explanationCode() { return explanationCode; }
	String explanationText() { return explanationText; }
	String safetyStatus() { return safetyStatus; }
	String safetyReasonCode() { return safetyReasonCode; }
	String safetyPolicyVersion() { return safetyPolicyVersion; }
	String safetyGuidanceCode() { return safetyGuidanceCode; }
	String safetyGuidance() { return safetyGuidance; }
	String resourceStatus() { return resourceStatus; }
	String resourcePolicyVersion() { return resourcePolicyVersion; }
	Instant resourcesResolvedAt() { return resourcesResolvedAt; }
	String phrasingStatus() { return phrasingStatus; }
}
