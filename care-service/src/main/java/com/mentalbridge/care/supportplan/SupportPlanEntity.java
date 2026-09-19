package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "support_plan")
class SupportPlanEntity {

	@Id private UUID id;
	private UUID userId;
	private UUID supportEvaluationId;
	private String status;
	@Version private long version;
	private String evaluationPolicyVersion;
	private Instant evaluatedAt;
	private String selectionPolicyVersion;
	private String resourcePolicyVersion;
	private Instant resourcesResolvedAt;
	private String entitlementPackage;
	private String entitlementSource;
	private String entitlementPolicyVersion;
	private long entitlementVersion;
	private Instant entitlementDecidedAt;
	private String rationaleCode;
	private String rationaleText;
	private String safetyStatus;
	private String safetyReasonCode;
	private String safetyPolicyVersion;
	private String safetyGuidanceCode;
	private String safetyGuidance;
	private short selectedResourceCount;
	private Instant createdAt;
	private Instant updatedAt;

	protected SupportPlanEntity() { }

	SupportPlanEntity(UUID id, UUID userId, UUID supportEvaluationId, String evaluationPolicyVersion,
			Instant evaluatedAt, String resourcePolicyVersion, Instant resourcesResolvedAt,
			String entitlementPackage, String entitlementSource, String entitlementPolicyVersion,
			long entitlementVersion, Instant entitlementDecidedAt, String rationaleText, String safetyStatus,
			String safetyReasonCode, String safetyPolicyVersion, String safetyGuidanceCode,
			String safetyGuidance, int selectedResourceCount, Instant createdAt) {
		this.id = id;
		this.userId = userId;
		this.supportEvaluationId = supportEvaluationId;
		this.status = "DRAFT";
		this.version = 0;
		this.evaluationPolicyVersion = evaluationPolicyVersion;
		this.evaluatedAt = evaluatedAt;
		this.selectionPolicyVersion = SupportPlanService.SELECTION_POLICY_VERSION;
		this.resourcePolicyVersion = resourcePolicyVersion;
		this.resourcesResolvedAt = resourcesResolvedAt;
		this.entitlementPackage = entitlementPackage;
		this.entitlementSource = entitlementSource;
		this.entitlementPolicyVersion = entitlementPolicyVersion;
		this.entitlementVersion = entitlementVersion;
		this.entitlementDecidedAt = entitlementDecidedAt;
		this.rationaleCode = "DOMAIN_AWARE_WELLBEING_SUPPORT";
		this.rationaleText = rationaleText;
		this.safetyStatus = safetyStatus;
		this.safetyReasonCode = safetyReasonCode;
		this.safetyPolicyVersion = safetyPolicyVersion;
		this.safetyGuidanceCode = safetyGuidanceCode;
		this.safetyGuidance = safetyGuidance;
		this.selectedResourceCount = (short) selectedResourceCount;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	UUID id() { return id; }
	UUID userId() { return userId; }
	UUID supportEvaluationId() { return supportEvaluationId; }
	String status() { return status; }
	long version() { return version; }
	String evaluationPolicyVersion() { return evaluationPolicyVersion; }
	Instant evaluatedAt() { return evaluatedAt; }
	String selectionPolicyVersion() { return selectionPolicyVersion; }
	String resourcePolicyVersion() { return resourcePolicyVersion; }
	Instant resourcesResolvedAt() { return resourcesResolvedAt; }
	String entitlementPackage() { return entitlementPackage; }
	String entitlementSource() { return entitlementSource; }
	String entitlementPolicyVersion() { return entitlementPolicyVersion; }
	long entitlementVersion() { return entitlementVersion; }
	Instant entitlementDecidedAt() { return entitlementDecidedAt; }
	String rationaleCode() { return rationaleCode; }
	String rationaleText() { return rationaleText; }
	String safetyStatus() { return safetyStatus; }
	String safetyReasonCode() { return safetyReasonCode; }
	String safetyPolicyVersion() { return safetyPolicyVersion; }
	String safetyGuidanceCode() { return safetyGuidanceCode; }
	String safetyGuidance() { return safetyGuidance; }
	short selectedResourceCount() { return selectedResourceCount; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }
}
