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
	private Short recurrenceDaysMask;
	private String preferredDaypart;
	private Short estimatedDurationMinutes;
	private LocalTime localTime;
	private String timezone;
	private LocalDate effectiveFrom;
	private LocalDate effectiveUntil;
	private String status;
	private Instant activatedAt;
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
		this.activatedAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static SupportPlanActivityScheduleEntity draftForSlot(UUID id, SupportPlanEntity plan,
			SupportPlanSlotEntity slot, String schedulePolicyVersion, String recurrenceType,
			Short recurrenceDaysMask, String preferredDaypart, int estimatedDurationMinutes,
			LocalTime localTime, String timezone, LocalDate effectiveFrom, Instant now) {
		var schedule = new SupportPlanActivityScheduleEntity();
		schedule.id = id;
		schedule.supportPlanId = plan.id();
		schedule.userId = plan.userId();
		schedule.ordinal = slot.ordinal();
		schedule.scheduleVersion = 1;
		schedule.sourcePlanVersion = plan.version();
		schedule.copySlot(slot);
		schedule.schedulePolicyVersion = schedulePolicyVersion;
		schedule.reminderEnabled = false;
		schedule.recurrenceType = recurrenceType;
		schedule.recurrenceDaysMask = recurrenceDaysMask;
		schedule.preferredDaypart = preferredDaypart;
		schedule.estimatedDurationMinutes = (short) estimatedDurationMinutes;
		schedule.localTime = localTime;
		schedule.timezone = timezone;
		schedule.effectiveFrom = effectiveFrom;
		schedule.status = "DRAFT";
		schedule.createdAt = now;
		schedule.updatedAt = now;
		return schedule;
	}

	static SupportPlanActivityScheduleEntity revisionForSlot(UUID id, SupportPlanEntity plan,
			SupportPlanSlotEntity slot, int scheduleVersion, String schedulePolicyVersion,
			String recurrenceType, Short recurrenceDaysMask, String preferredDaypart,
			int estimatedDurationMinutes, LocalTime localTime, String timezone,
			LocalDate effectiveFrom, LocalDate effectiveUntil, boolean reminderEnabled,
			String status, Instant now) {
		var schedule = draftForSlot(id, plan, slot, schedulePolicyVersion, recurrenceType,
				recurrenceDaysMask, preferredDaypart, estimatedDurationMinutes, localTime,
				timezone, effectiveFrom, now);
		schedule.scheduleVersion = scheduleVersion;
		schedule.effectiveUntil = effectiveUntil;
		schedule.reminderEnabled = reminderEnabled;
		schedule.status = status;
		if ("ACTIVE".equals(status)) schedule.activatedAt = now;
		return schedule;
	}

	static SupportPlanActivityScheduleEntity revisionForAction(UUID id, SupportPlanEntity plan,
			PlanActionEntity action, int scheduleVersion, String schedulePolicyVersion,
			String recurrenceType, Short recurrenceDaysMask, String preferredDaypart,
			int estimatedDurationMinutes, LocalTime localTime, String timezone,
			LocalDate effectiveFrom, LocalDate effectiveUntil, boolean reminderEnabled,
			String status, Instant now) {
		var schedule = new SupportPlanActivityScheduleEntity();
		schedule.id = id;
		schedule.supportPlanId = plan.id();
		schedule.userId = plan.userId();
		schedule.ordinal = action.ordinal();
		schedule.scheduleVersion = scheduleVersion;
		schedule.sourcePlanVersion = plan.version();
		schedule.sourceActionId = action.id();
		schedule.sourceActionVersion = action.version();
		schedule.sourceActionType = action.actionType();
		schedule.sourceTitle = action.presentationTitle();
		schedule.schedulePolicyVersion = schedulePolicyVersion;
		schedule.reminderEnabled = reminderEnabled;
		schedule.recurrenceType = recurrenceType;
		schedule.recurrenceDaysMask = recurrenceDaysMask;
		schedule.preferredDaypart = preferredDaypart;
		schedule.estimatedDurationMinutes = (short) estimatedDurationMinutes;
		schedule.localTime = localTime;
		schedule.timezone = timezone;
		schedule.effectiveFrom = effectiveFrom;
		schedule.effectiveUntil = effectiveUntil;
		schedule.status = status;
		if ("ACTIVE".equals(status)) schedule.activatedAt = now;
		schedule.createdAt = now;
		schedule.updatedAt = now;
		return schedule;
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
		this.activatedAt = now;
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
	Short recurrenceDaysMask() { return recurrenceDaysMask; }
	String preferredDaypart() { return preferredDaypart; }
	Short estimatedDurationMinutes() { return estimatedDurationMinutes; }
	LocalTime localTime() { return localTime; }
	String timezone() { return timezone; }
	LocalDate effectiveFrom() { return effectiveFrom; }
	LocalDate effectiveUntil() { return effectiveUntil; }
	String status() { return status; }
	Instant activatedAt() { return activatedAt; }

	void activate(long planVersion, SupportPlanSlotEntity slot, Instant now) {
		if (!"DRAFT".equals(status)) return;
		this.sourcePlanVersion = planVersion;
		copySlot(slot);
		this.status = "ACTIVE";
		this.activatedAt = now;
		this.updatedAt = now;
	}

	void activate(long planVersion, PlanActionEntity action, Instant now) {
		if (!"DRAFT".equals(status)) return;
		this.sourcePlanVersion = planVersion;
		this.sourceActionVersion = action.version();
		this.sourceActionType = action.actionType();
		this.sourceTitle = action.presentationTitle();
		this.status = "ACTIVE";
		this.activatedAt = now;
		this.updatedAt = now;
	}

	void syncDraftSlot(long planVersion, SupportPlanSlotEntity slot, Instant now) {
		if (!"DRAFT".equals(status)) return;
		this.sourcePlanVersion = planVersion;
		copySlot(slot);
		this.updatedAt = now;
	}

	void pause(Instant now) {
		if (!"ACTIVE".equals(status)) return;
		this.status = "PAUSED";
		this.updatedAt = now;
	}

	void resume(Instant now) {
		if (!"PAUSED".equals(status)) return;
		this.status = "ACTIVE";
		this.updatedAt = now;
	}

	void end(LocalDate lastLocalDate, Instant now) {
		if ("ENDED".equals(status)) return;
		this.status = "ENDED";
		this.effectiveUntil = lastLocalDate.isBefore(effectiveFrom) ? effectiveFrom : lastLocalDate;
		this.updatedAt = now;
	}

	private void copySlot(SupportPlanSlotEntity slot) {
		var resource = slot.selectedResource();
		if (resource == null) {
			throw new IllegalArgumentException("A schedule requires a selected resource");
		}
		this.sourceSlotKey = slot.slotKey();
		this.sourceResourceId = resource.resourceId();
		this.sourceContentVersion = resource.contentVersion();
		this.sourceTitle = resource.title();
	}
}
