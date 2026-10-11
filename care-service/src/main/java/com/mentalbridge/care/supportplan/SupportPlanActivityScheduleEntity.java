package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_plan_activity_schedule")
class SupportPlanActivityScheduleEntity {

	@Id private UUID id;
	private UUID supportPlanId;
	private UUID userId;
	private short ordinal;
	private int scheduleVersion;
	private long sourcePlanVersion;
	private String sourceSlotKey;
	private UUID sourceResourceId;
	private Long sourceContentVersion;
	private UUID sourceActionId;
	private Long sourceActionVersion;
	private String sourceActionType;
	private String sourceTitle;
	private String schedulePolicyVersion;
	private boolean reminderEnabled;
	private String recurrenceType;
	private Short recurrenceDayOfWeek;
	private LocalTime localTime;
	private String timezone;
	private LocalDate effectiveFrom;
	private LocalDate effectiveUntil;
	private String status;
	private Instant createdAt;
	private Instant updatedAt;

	protected SupportPlanActivityScheduleEntity() { }

	SupportPlanActivityScheduleEntity(UUID id, UUID supportPlanId, UUID userId, int ordinal,
			long sourcePlanVersion, SupportPlanSlotEntity slot, String recurrenceType,
			Short recurrenceDayOfWeek, LocalTime localTime, String timezone, LocalDate effectiveFrom,
			Instant now) {
		var resource = slot.selectedResource();
		this.id = id;
		this.supportPlanId = supportPlanId;
		this.userId = userId;
		this.ordinal = (short) ordinal;
		this.scheduleVersion = 1;
		this.sourcePlanVersion = sourcePlanVersion;
		this.sourceSlotKey = slot.slotKey();
		this.sourceResourceId = resource.resourceId();
		this.sourceContentVersion = resource.contentVersion();
		this.sourceTitle = resource.title();
		this.schedulePolicyVersion = null;
		this.reminderEnabled = false;
		this.recurrenceType = recurrenceType;
		this.recurrenceDayOfWeek = recurrenceDayOfWeek;
		this.localTime = localTime;
		this.timezone = timezone;
		this.effectiveFrom = effectiveFrom;
		this.status = "ACTIVE";
		this.createdAt = now;
		this.updatedAt = now;
	}

	SupportPlanActivityScheduleEntity(UUID id, UUID supportPlanId, UUID userId, int ordinal,
			long sourcePlanVersion, PlanActionEntity action, String schedulePolicyVersion,
			String recurrenceType, Short recurrenceDayOfWeek, LocalTime localTime, String timezone,
			LocalDate effectiveFrom, boolean reminderEnabled, Instant now) {
		this.id = id;
		this.supportPlanId = supportPlanId;
		this.userId = userId;
		this.ordinal = (short) ordinal;
		this.scheduleVersion = 1;
		this.sourcePlanVersion = sourcePlanVersion;
		this.sourceActionId = action.id();
		this.sourceActionVersion = action.version();
		this.sourceActionType = action.actionType();
		this.sourceTitle = action.presentationTitle();
		this.schedulePolicyVersion = schedulePolicyVersion;
		this.reminderEnabled = reminderEnabled;
		this.recurrenceType = recurrenceType;
		this.recurrenceDayOfWeek = recurrenceDayOfWeek;
		this.localTime = localTime;
		this.timezone = timezone;
		this.effectiveFrom = effectiveFrom;
		this.status = "ACTIVE";
		this.createdAt = now;
		this.updatedAt = now;
	}

	UUID id() { return id; }
	UUID supportPlanId() { return supportPlanId; }
	UUID userId() { return userId; }
	short ordinal() { return ordinal; }
	int scheduleVersion() { return scheduleVersion; }
	long sourcePlanVersion() { return sourcePlanVersion; }
	String sourceSlotKey() { return sourceSlotKey; }
	UUID sourceResourceId() { return sourceResourceId; }
	Long sourceContentVersion() { return sourceContentVersion; }
	UUID sourceActionId() { return sourceActionId; }
	Long sourceActionVersion() { return sourceActionVersion; }
	String sourceActionType() { return sourceActionType; }
	String sourceTitle() { return sourceTitle; }
	String schedulePolicyVersion() { return schedulePolicyVersion; }
	boolean reminderEnabled() { return reminderEnabled; }
	String recurrenceType() { return recurrenceType; }
	Short recurrenceDayOfWeek() { return recurrenceDayOfWeek; }
	LocalTime localTime() { return localTime; }
	String timezone() { return timezone; }
	LocalDate effectiveFrom() { return effectiveFrom; }
	LocalDate effectiveUntil() { return effectiveUntil; }
	String status() { return status; }

	void pause(Instant now) {
		this.status = "PAUSED";
		this.updatedAt = now;
	}

	void resume(Instant now) {
		this.status = "ACTIVE";
		this.updatedAt = now;
	}

	void end(LocalDate lastLocalDate, Instant now) {
		this.status = "ENDED";
		this.effectiveUntil = lastLocalDate.isBefore(effectiveFrom) ? effectiveFrom : lastLocalDate;
		this.updatedAt = now;
	}
}
