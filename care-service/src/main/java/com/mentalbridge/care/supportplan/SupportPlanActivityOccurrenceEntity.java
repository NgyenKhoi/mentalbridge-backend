package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

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
	private long sourceContentVersion;
	private String sourceTitle;
	@Version private long version;
	private Instant createdAt;
	private Instant updatedAt;
	private Instant completedAt;
	private Instant skippedAt;
	private Instant cancelledAt;

	protected SupportPlanActivityOccurrenceEntity() { }

	UUID id() { return id; }
	UUID activityScheduleId() { return activityScheduleId; }
	UUID supportPlanId() { return supportPlanId; }
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
	long sourceContentVersion() { return sourceContentVersion; }
	String sourceTitle() { return sourceTitle; }
	long version() { return version; }
	Instant updatedAt() { return updatedAt; }
	Instant completedAt() { return completedAt; }
	Instant skippedAt() { return skippedAt; }
	Instant cancelledAt() { return cancelledAt; }

	void complete(Instant now) {
		this.state = "COMPLETED";
		this.completedAt = now;
		this.updatedAt = now;
	}

	void skip(Instant now) {
		this.state = "SKIPPED";
		this.skippedAt = now;
		this.updatedAt = now;
	}
}
