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
	private int modelVersion;
	private String planPolicyVersion;
	private short actionCount;
	private String evaluationPolicyVersion;
	private Instant evaluatedAt;
	private String selectionPolicyVersion;
	private String resourcePolicyVersion;
	private Instant resourcesResolvedAt;
	private UUID personalizationId;
	private Long personalizationRevision;
	private String personalizationSchemaVersion;
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
	private Short selectedResourceCount;
	private Instant createdAt;
	private Instant updatedAt;
	private Instant activatedAt;
	private Instant completedAt;
	private String completionReason;
	private Instant supersededAt;
	private Instant discardedAt;

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
		this.modelVersion = 2;
		this.planPolicyVersion = null;
		this.actionCount = 0;
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

	static SupportPlanEntity nativeDraft(UUID id, UUID userId, UUID supportEvaluationId,
			String evaluationPolicyVersion, Instant evaluatedAt, String planPolicyVersion,
			String entitlementPackage, String entitlementSource, String entitlementPolicyVersion,
			long entitlementVersion, Instant entitlementDecidedAt, String rationaleCode,
			String rationaleText, String safetyStatus, String safetyReasonCode,
			String safetyPolicyVersion, String safetyGuidanceCode, String safetyGuidance,
			int actionCount, Instant createdAt) {
		if (actionCount < 1 || actionCount > 20) {
			throw new IllegalArgumentException("PlanAction count must be 1..20");
		}
		var plan = new SupportPlanEntity();
		plan.id = id;
		plan.userId = userId;
		plan.supportEvaluationId = supportEvaluationId;
		plan.status = "DRAFT";
		plan.version = 0;
		plan.modelVersion = 3;
		plan.planPolicyVersion = planPolicyVersion;
		plan.actionCount = (short) actionCount;
		plan.evaluationPolicyVersion = evaluationPolicyVersion;
		plan.evaluatedAt = evaluatedAt;
		plan.entitlementPackage = entitlementPackage;
		plan.entitlementSource = entitlementSource;
		plan.entitlementPolicyVersion = entitlementPolicyVersion;
		plan.entitlementVersion = entitlementVersion;
		plan.entitlementDecidedAt = entitlementDecidedAt;
		plan.rationaleCode = rationaleCode;
		plan.rationaleText = rationaleText;
		plan.safetyStatus = safetyStatus;
		plan.safetyReasonCode = safetyReasonCode;
		plan.safetyPolicyVersion = safetyPolicyVersion;
		plan.safetyGuidanceCode = safetyGuidanceCode;
		plan.safetyGuidance = safetyGuidance;
		plan.createdAt = createdAt;
		plan.updatedAt = createdAt;
		return plan;
	}

	UUID id() { return id; }
	UUID userId() { return userId; }
	UUID supportEvaluationId() { return supportEvaluationId; }
	String status() { return status; }
	long version() { return version; }
	int modelVersion() { return modelVersion; }
	String planPolicyVersion() { return planPolicyVersion; }
	short actionCount() { return actionCount; }
	String evaluationPolicyVersion() { return evaluationPolicyVersion; }
	Instant evaluatedAt() { return evaluatedAt; }
	String selectionPolicyVersion() { return selectionPolicyVersion; }
	String resourcePolicyVersion() { return resourcePolicyVersion; }
	Instant resourcesResolvedAt() { return resourcesResolvedAt; }
	UUID personalizationId() { return personalizationId; }
	Long personalizationRevision() { return personalizationRevision; }
	String personalizationSchemaVersion() { return personalizationSchemaVersion; }
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
	Short selectedResourceCount() { return selectedResourceCount; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }
	Instant activatedAt() { return activatedAt; }
	Instant completedAt() { return completedAt; }
	String completionReason() { return completionReason; }
	Instant supersededAt() { return supersededAt; }
	Instant discardedAt() { return discardedAt; }

	void snapshotPersonalization(SupportPlanPersonalizationService.PersonalizationSnapshot personalization) {
		if (personalization == null) return;
		this.personalizationId = personalization.personalizationId();
		this.personalizationRevision = personalization.revision();
		this.personalizationSchemaVersion = personalization.schemaVersion();
	}

	void copyPersonalizationFrom(SupportPlanEntity source) {
		this.personalizationId = source.personalizationId;
		this.personalizationRevision = source.personalizationRevision;
		this.personalizationSchemaVersion = source.personalizationSchemaVersion;
	}


	void changeChoices(int selectedResourceCount, Instant changedAt) {
		this.selectedResourceCount = (short) selectedResourceCount;
		this.updatedAt = changedAt;
	}

	void activate(Instant activatedAt) {
		this.status = "ACTIVE";
		this.activatedAt = activatedAt;
		this.updatedAt = activatedAt;
	}

	void pause(Instant changedAt) {
		this.status = "PAUSED";
		this.updatedAt = changedAt;
	}

	void resume(Instant changedAt) {
		this.status = "ACTIVE";
		this.updatedAt = changedAt;
	}

	void actionChanged(Instant changedAt) {
		this.updatedAt = changedAt.isAfter(this.updatedAt) ? changedAt : this.updatedAt.plusNanos(1_000);
	}

	void complete(String completionReason, Instant changedAt) {
		this.status = "COMPLETED";
		this.completedAt = changedAt;
		this.completionReason = completionReason;
		this.updatedAt = changedAt;
	}

	void supersede(Instant changedAt) {
		this.status = "SUPERSEDED";
		this.supersededAt = changedAt;
		this.updatedAt = changedAt;
	}

	void discard(Instant changedAt) {
		this.status = "DISCARDED";
		this.discardedAt = changedAt;
		this.updatedAt = changedAt;
	}
}
