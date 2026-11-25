package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.util.UUID;

import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "support_plan_action")
class PlanActionEntity {

	@Id private UUID id;
	private UUID supportPlanId;
	private UUID planFocusId;
	private UUID userId;
	private short ordinal;
	private String actionType;
	private String purposeCode;
	private String rationale;
	private String sourceOwner;
	private String sourceKind;
	private String sourceId;
	private String sourceRevision;
	private String sourcePolicyVersion;
	private UUID sourcePublicationId;
	private String sourceEligibilityPolicyVersion;
	private Instant sourceVerifiedAt;
	private String provenanceType;
	private String provenanceReferenceId;
	private String provenanceReferenceVersion;
	private Instant provenanceAcceptedAt;
	private String presentationTitle;
	private String presentationSummary;
	private String presentationInstructions;
	private String presentationExternalUrl;
	private String status;
	@Version private long version;
	private Instant createdAt;
	private Instant updatedAt;
	private Instant activatedAt;
	private Instant endedAt;

	protected PlanActionEntity() { }

	PlanActionEntity(UUID id, UUID supportPlanId, UUID planFocusId, UUID userId, int ordinal,
			String actionType, String purposeCode, String rationale, SourceReference source,
			Provenance provenance, Presentation presentation, Instant now) {
		new PlanActionSourcePolicy().validateSource(actionType, source, provenance);
		this.id = id;
		this.supportPlanId = supportPlanId;
		this.planFocusId = planFocusId;
		this.userId = userId;
		this.ordinal = (short) ordinal;
		this.actionType = actionType;
		this.purposeCode = purposeCode;
		this.rationale = rationale;
		if (source != null) {
			this.sourceOwner = source.owner();
			this.sourceKind = source.kind();
			this.sourceId = source.id();
			this.sourceRevision = source.revision();
			this.sourcePolicyVersion = source.policyVersion();
			this.sourcePublicationId = source.publicationId();
			this.sourceEligibilityPolicyVersion = source.eligibilityPolicyVersion();
			this.sourceVerifiedAt = source.verifiedAt();
		}
		this.provenanceType = provenance.type();
		this.provenanceReferenceId = provenance.referenceId();
		this.provenanceReferenceVersion = provenance.referenceVersion();
		this.provenanceAcceptedAt = provenance.acceptedAt();
		this.presentationTitle = presentation.title();
		this.presentationSummary = presentation.summary();
		this.presentationInstructions = presentation.instructions();
		this.presentationExternalUrl = presentation.externalUrl();
		this.status = "DRAFT";
		this.version = 0;
		this.createdAt = now;
		this.updatedAt = now;
	}

	UUID id() { return id; }
	UUID supportPlanId() { return supportPlanId; }
	UUID planFocusId() { return planFocusId; }
	UUID userId() { return userId; }
	short ordinal() { return ordinal; }
	String actionType() { return actionType; }
	String purposeCode() { return purposeCode; }
	String rationale() { return rationale; }
	String sourceOwner() { return sourceOwner; }
	String sourceKind() { return sourceKind; }
	String sourceId() { return sourceId; }
	String sourceRevision() { return sourceRevision; }
	String sourcePolicyVersion() { return sourcePolicyVersion; }
	UUID sourcePublicationId() { return sourcePublicationId; }
	String sourceEligibilityPolicyVersion() { return sourceEligibilityPolicyVersion; }
	Instant sourceVerifiedAt() { return sourceVerifiedAt; }
	String provenanceType() { return provenanceType; }
	String provenanceReferenceId() { return provenanceReferenceId; }
	String provenanceReferenceVersion() { return provenanceReferenceVersion; }
	Instant provenanceAcceptedAt() { return provenanceAcceptedAt; }
	String presentationTitle() { return presentationTitle; }
	String presentationSummary() { return presentationSummary; }
	String presentationInstructions() { return presentationInstructions; }
	String presentationExternalUrl() { return presentationExternalUrl; }
	String status() { return status; }
	long version() { return version; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }
	Instant activatedAt() { return activatedAt; }
	Instant endedAt() { return endedAt; }

	void activate(Instant now) {
		this.status = "ACTIVE";
		this.activatedAt = now;
		this.updatedAt = now;
	}

	void end(Instant now) {
		this.status = "ENDED";
		this.endedAt = now;
		this.updatedAt = now;
	}

	record Presentation(String title, String summary, String instructions, String externalUrl) {
		Presentation {
			if (title == null || title.isBlank() || title.length() > 255
					|| invalidOptional(summary, 4096) || invalidOptional(instructions, 2048)
					|| invalidOptional(externalUrl, 2048)) {
				throw new IllegalArgumentException("PlanAction presentation is invalid");
			}
		}

		private static boolean invalidOptional(String value, int maximumLength) {
			return value != null && (value.isBlank() || value.length() > maximumLength);
		}
	}
}
