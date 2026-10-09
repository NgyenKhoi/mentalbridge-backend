package com.mentalbridge.identity.reporting;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "platform_report_schedule")
class ReportScheduleEntity {
	@Id UUID id;
	@Enumerated(EnumType.STRING)
	@Column(name = "report_type", nullable = false, length = 64) PlatformReportType reportType;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16) ReportCadence cadence;
	@Column(nullable = false, length = 64) String timezone;
	@Column(name = "local_time", nullable = false) LocalTime localTime;
	@Column(name = "period_days", nullable = false) int periodDays;
	@Column(name = "recipient_group", nullable = false, length = 16) String recipientGroup;
	@Column(name = "delivery_target", nullable = false, length = 24) String deliveryTarget;
	@Column(nullable = false, length = 16) String status;
	@Column(name = "created_by", nullable = false, updatable = false) UUID createdBy;
	@Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
	@Column(name = "updated_at", nullable = false) Instant updatedAt;
	@Column(name = "next_run_at", nullable = false) Instant nextRunAt;
	@Column(name = "last_failure_code", length = 64) String lastFailureCode;
	@Version @Column(nullable = false) long version;

	protected ReportScheduleEntity() { }

	static ReportScheduleEntity create(UUID actor, ReportScheduleService.ScheduleRequest request, Instant now) {
		var schedule = new ReportScheduleEntity();
		schedule.id = UUID.randomUUID();
		schedule.createdBy = actor;
		schedule.createdAt = now;
		schedule.replace(request, now);
		return schedule;
	}

	void replace(ReportScheduleService.ScheduleRequest request, Instant now) {
		reportType = request.reportType();
		cadence = request.cadence();
		timezone = request.timezone();
		localTime = request.localTime();
		periodDays = request.periodDays();
		recipientGroup = request.recipientGroup();
		deliveryTarget = request.deliveryTarget();
		status = request.enabled() ? "ACTIVE" : "PAUSED";
		nextRunAt = cadence.nextAfter(now, timezone, localTime);
		lastFailureCode = null;
		updatedAt = now;
	}
}
