package com.mentalbridge.care.support;

import java.util.UUID;

import com.mentalbridge.care.assessment.SafetyStatus;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_evaluation_v2_safety")
class SupportEvaluationV2SafetyEntity {

	@Id
	private UUID supportEvaluationId;

	private UUID sourceAssessmentId;

	private String instrument;

	private String triggerCode;

	@Enumerated(EnumType.STRING)
	private SafetyStatus safetyStatus;

	private String safetyPolicyVersion;

	@Enumerated(EnumType.STRING)
	private SafetyReasonCode reasonCode;

	protected SupportEvaluationV2SafetyEntity() {
	}

	SupportEvaluationV2SafetyEntity(UUID supportEvaluationId, UUID sourceAssessmentId, String instrument,
			String triggerCode, SafetyStatus safetyStatus, String safetyPolicyVersion, SafetyReasonCode reasonCode) {
		this.supportEvaluationId = supportEvaluationId;
		this.sourceAssessmentId = sourceAssessmentId;
		this.instrument = instrument;
		this.triggerCode = triggerCode;
		this.safetyStatus = safetyStatus;
		this.safetyPolicyVersion = safetyPolicyVersion;
		this.reasonCode = reasonCode;
	}

	UUID sourceAssessmentId() { return sourceAssessmentId; }
	String instrument() { return instrument; }
	String triggerCode() { return triggerCode; }
	SafetyStatus safetyStatus() { return safetyStatus; }
	String safetyPolicyVersion() { return safetyPolicyVersion; }
	SafetyReasonCode reasonCode() { return reasonCode; }
}
