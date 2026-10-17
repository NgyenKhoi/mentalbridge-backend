package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "plan_change_request")
class PlanChangeRequestEntity {

	@Id private UUID id;
	private UUID userId;
	private UUID specialistId;
	private UUID sourceProposalId;
	private long sourceProposalVersion;
	private UUID sourceAppointmentId;
	private UUID sourceSummaryId;
	private long sourceSummaryVersion;
	private UUID sourceCompletionFactId;
	private String proposalReasonCode;
	private UUID resourceId;
	private long resourceVersion;
	private String proposalTitle;
	private String proposalDetails;
	private UUID currentSupportPlanId;
	private long currentSupportPlanVersion;
	private UUID replacementSupportPlanId;
	private Long replacementSupportPlanVersion;
	private String targetSlotId;
	private UUID currentResourceId;
	private Long currentResourceVersion;
	private String currentResourceTitle;
	private String status;
	private String outcomeCode;
	private String idempotencyKey;
	private String requestHash;
	private String decisionIdempotencyKey;
	private String decisionHash;
	@Version private long version;
	private Instant reviewedAt;
	private Instant decidedAt;
	private Instant createdAt;
	private Instant updatedAt;

	protected PlanChangeRequestEntity() { }

	PlanChangeRequestEntity(UUID id, UUID userId, UUID specialistId, UUID sourceProposalId,
			long sourceProposalVersion, UUID sourceAppointmentId, UUID sourceSummaryId,
			long sourceSummaryVersion, UUID sourceCompletionFactId, String proposalReasonCode,
			UUID resourceId, long resourceVersion, String proposalTitle, String proposalDetails,
			UUID currentSupportPlanId, long currentSupportPlanVersion, String targetSlotId,
			UUID currentResourceId, Long currentResourceVersion, String currentResourceTitle, String idempotencyKey,
			String requestHash, Instant now) {
		this.id = id;
		this.userId = userId;
		this.specialistId = specialistId;
		this.sourceProposalId = sourceProposalId;
		this.sourceProposalVersion = sourceProposalVersion;
		this.sourceAppointmentId = sourceAppointmentId;
		this.sourceSummaryId = sourceSummaryId;
		this.sourceSummaryVersion = sourceSummaryVersion;
		this.sourceCompletionFactId = sourceCompletionFactId;
		this.proposalReasonCode = proposalReasonCode;
		this.resourceId = resourceId;
		this.resourceVersion = resourceVersion;
		this.proposalTitle = proposalTitle;
		this.proposalDetails = proposalDetails;
		this.currentSupportPlanId = currentSupportPlanId;
		this.currentSupportPlanVersion = currentSupportPlanVersion;
		this.targetSlotId = targetSlotId;
		this.currentResourceId = currentResourceId;
		this.currentResourceVersion = currentResourceVersion;
		this.currentResourceTitle = currentResourceTitle;
		this.status = "READY_FOR_REVIEW";
		this.outcomeCode = "PROPOSAL_ADMISSIBLE";
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.version = 0;
		this.reviewedAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}

	UUID id() { return id; }
	UUID userId() { return userId; }
	UUID specialistId() { return specialistId; }
	UUID sourceProposalId() { return sourceProposalId; }
	long sourceProposalVersion() { return sourceProposalVersion; }
	UUID sourceAppointmentId() { return sourceAppointmentId; }
	UUID sourceSummaryId() { return sourceSummaryId; }
	long sourceSummaryVersion() { return sourceSummaryVersion; }
	UUID sourceCompletionFactId() { return sourceCompletionFactId; }
	String proposalReasonCode() { return proposalReasonCode; }
	UUID resourceId() { return resourceId; }
	long resourceVersion() { return resourceVersion; }
	String proposalTitle() { return proposalTitle; }
	String proposalDetails() { return proposalDetails; }
	UUID currentSupportPlanId() { return currentSupportPlanId; }
	long currentSupportPlanVersion() { return currentSupportPlanVersion; }
	UUID replacementSupportPlanId() { return replacementSupportPlanId; }
	Long replacementSupportPlanVersion() { return replacementSupportPlanVersion; }
	String targetSlotId() { return targetSlotId; }
	UUID currentResourceId() { return currentResourceId; }
	Long currentResourceVersion() { return currentResourceVersion; }
	String currentResourceTitle() { return currentResourceTitle; }
	String status() { return status; }
	String outcomeCode() { return outcomeCode; }
	String idempotencyKey() { return idempotencyKey; }
	String requestHash() { return requestHash; }
	String decisionIdempotencyKey() { return decisionIdempotencyKey; }
	String decisionHash() { return decisionHash; }
	long version() { return version; }
	Instant reviewedAt() { return reviewedAt; }
	Instant decidedAt() { return decidedAt; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }

	void accept(String key, String hash, UUID replacementPlanId, long replacementPlanVersion, Instant now) {
		status = "ACCEPTED";
		outcomeCode = "PROPOSAL_APPLIED";
		replacementSupportPlanId = replacementPlanId;
		replacementSupportPlanVersion = replacementPlanVersion;
		decisionIdempotencyKey = key;
		decisionHash = hash;
		decidedAt = now;
		updatedAt = now;
	}

	void reject(String key, String hash, Instant now) {
		status = "REJECTED";
		outcomeCode = "USER_REJECTED";
		decisionIdempotencyKey = key;
		decisionHash = hash;
		decidedAt = now;
		updatedAt = now;
	}
}
