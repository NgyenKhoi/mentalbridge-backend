package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.EvidenceReference;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "support_plan_activity_occurrence")
class SupportPlanActivityOccurrenceEntity {

	@Id private UUID id;
	private UUID activityScheduleId;
	private UUID supportPlanId;
	private UUID userId;
	private int scheduleVersion;
	private LocalDate localDate;
	private LocalTime localTime;
	private String timezone;
	private Instant scheduledAt;
	private String state;
	private String stateReason;
	private long sourcePlanVersion;
	private String sourceSlotKey;
	private UUID sourceResourceId;
	private Long sourceContentVersion;
	private UUID sourceActionId;
	private Long sourceActionVersion;
	private String sourceActionType;
	private String sourceTitle;
	private String evidenceOwner;
	private String evidenceKind;
	private String evidenceId;
	private String evidenceRevision;
	private Instant evidenceOccurredAt;
	private Instant evidenceVerifiedAt;
	@Version private long version;
	private Instant createdAt;
	private Instant updatedAt;
	private Instant completedAt;
	private Instant skippedAt;
	private Instant cancelledAt;
	private boolean hidden;
	private String helpfulness;
	private String barrierCode;
	private String reflection;
	private boolean summaryReuseApproved;
	private Instant engagementUpdatedAt;

	protected SupportPlanActivityOccurrenceEntity() { }

	UUID id() { return id; }
	UUID activityScheduleId() { return activityScheduleId; }
	UUID supportPlanId() { return supportPlanId; }
	UUID userId() { return userId; }
	int scheduleVersion() { return scheduleVersion; }
	LocalDate localDate() { return localDate; }
	LocalTime localTime() { return localTime; }
	String timezone() { return timezone; }
	Instant scheduledAt() { return scheduledAt; }
	String state() { return state; }
	String stateReason() { return stateReason; }
	long sourcePlanVersion() { return sourcePlanVersion; }
	String sourceSlotKey() { return sourceSlotKey; }
	UUID sourceResourceId() { return sourceResourceId; }
	Long sourceContentVersion() { return sourceContentVersion; }
	UUID sourceActionId() { return sourceActionId; }
	Long sourceActionVersion() { return sourceActionVersion; }
	String sourceActionType() { return sourceActionType; }
	String sourceTitle() { return sourceTitle; }
	String evidenceOwner() { return evidenceOwner; }
	String evidenceKind() { return evidenceKind; }
	String evidenceId() { return evidenceId; }
	String evidenceRevision() { return evidenceRevision; }
	Instant evidenceOccurredAt() { return evidenceOccurredAt; }
	Instant evidenceVerifiedAt() { return evidenceVerifiedAt; }
	long version() { return version; }
	Instant updatedAt() { return updatedAt; }
	Instant completedAt() { return completedAt; }
	Instant skippedAt() { return skippedAt; }
	Instant cancelledAt() { return cancelledAt; }
	boolean hidden() { return hidden; }
	String helpfulness() { return helpfulness; }
	String barrierCode() { return barrierCode; }
	String reflection() { return reflection; }
	boolean summaryReuseApproved() { return summaryReuseApproved; }
	Instant engagementUpdatedAt() { return engagementUpdatedAt; }

	boolean engagementEquals(String desiredState, boolean desiredHidden, String desiredHelpfulness,
			String desiredBarrierCode, String desiredReflection, boolean desiredSummaryReuseApproved) {
		return state.equals(desiredState) && hidden == desiredHidden
				&& java.util.Objects.equals(helpfulness, desiredHelpfulness)
				&& java.util.Objects.equals(barrierCode, desiredBarrierCode)
				&& java.util.Objects.equals(reflection, desiredReflection)
				&& summaryReuseApproved == desiredSummaryReuseApproved;
	}

	boolean evidenceEquals(EvidenceReference evidence) {
		return "COMPLETED".equals(state)
				&& java.util.Objects.equals(evidenceOwner, evidence.owner())
				&& java.util.Objects.equals(evidenceKind, evidence.kind())
				&& java.util.Objects.equals(evidenceId, evidence.id())
				&& java.util.Objects.equals(evidenceRevision, evidence.revision())
				&& java.util.Objects.equals(evidenceOccurredAt, evidence.occurredAt());
	}

	void completeWithEvidence(EvidenceReference evidence, Instant now) {
		this.state = "COMPLETED";
		this.stateReason = null;
		this.completedAt = now;
		this.skippedAt = null;
		this.cancelledAt = null;
		this.evidenceOwner = evidence.owner();
		this.evidenceKind = evidence.kind();
		this.evidenceId = evidence.id();
		this.evidenceRevision = evidence.revision();
		this.evidenceOccurredAt = evidence.occurredAt();
		this.evidenceVerifiedAt = evidence.verifiedAt();
		this.updatedAt = now;
	}

	void replaceEngagement(String desiredState, boolean desiredHidden, String desiredHelpfulness,
			String desiredBarrierCode, String desiredReflection, boolean desiredSummaryReuseApproved, Instant now) {
		this.state = desiredState;
		this.stateReason = null;
		this.completedAt = "COMPLETED".equals(desiredState) ? now : null;
		this.skippedAt = "SKIPPED".equals(desiredState) ? now : null;
		this.cancelledAt = null;
		this.hidden = desiredHidden;
		this.helpfulness = desiredHelpfulness;
		this.barrierCode = desiredBarrierCode;
		this.reflection = desiredReflection;
		this.summaryReuseApproved = desiredSummaryReuseApproved;
		this.engagementUpdatedAt = now;
		this.updatedAt = now;
	}

	void deleteEngagement(Instant now) {
		replaceEngagement("SCHEDULED", false, null, null, null, false, now);
	}

	void changeOpenNativeUserState(String desiredState, Instant now) {
		this.state = desiredState;
		this.stateReason = null;
		this.completedAt = null;
		this.skippedAt = "SKIPPED".equals(desiredState) ? now : null;
		this.cancelledAt = null;
		this.engagementUpdatedAt = now;
		this.updatedAt = now;
	}
}
