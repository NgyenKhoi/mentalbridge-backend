package com.mentalbridge.identity.reporting;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.RoleCode;

@Service
public class ReportScheduleService {
	private final ReportScheduleRepository schedules;
	private final PlatformReportService reports;
	private final AccountRepository accounts;
	private final Clock clock;

	ReportScheduleService(ReportScheduleRepository schedules, PlatformReportService reports,
			AccountRepository accounts, Clock clock) {
		this.schedules = schedules;
		this.reports = reports;
		this.accounts = accounts;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<ScheduleResponse> list() {
		return schedules.findByStatusNotOrderByCreatedAtDescIdDesc("DELETED", PageRequest.of(0, 50))
				.stream().map(this::response).toList();
	}

	@Transactional
	public ScheduleResponse create(UUID actor, ScheduleRequest request) {
		validate(request);
		var account = accounts.findByIdForUpdate(actor).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
		if (account.role() != RoleCode.ADMIN || account.status() != AccountStatus.ACTIVE) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		}
		if (schedules.countByStatusNot("DELETED") >= 50) throw new ResponseStatusException(HttpStatus.CONFLICT, "Schedule limit reached");
		return response(schedules.saveAndFlush(ReportScheduleEntity.create(actor, request, clock.instant())));
	}

	@Transactional
	public ScheduleResponse update(UUID id, ScheduleRequest request, long expectedVersion) {
		validate(request);
		var schedule = current(id, expectedVersion);
		schedule.replace(request, clock.instant());
		return response(schedules.saveAndFlush(schedule));
	}

	@Transactional
	public void delete(UUID id, long expectedVersion) {
		var schedule = current(id, expectedVersion);
		schedule.status = "DELETED";
		schedule.updatedAt = clock.instant();
	}

	@Transactional
	public boolean enqueueNext() {
		Instant now = clock.instant();
		var found = schedules.due(now, PageRequest.of(0, 1));
		if (found.isEmpty()) return false;
		var schedule = found.getFirst();
		var account = accounts.findById(schedule.createdBy).orElse(null);
		if (account == null || account.role() != RoleCode.ADMIN || account.status() != AccountStatus.ACTIVE) {
			schedule.status = "PAUSED";
			schedule.lastFailureCode = "ADMIN_ACCESS_UNAVAILABLE";
			schedule.updatedAt = now;
			return true;
		}
		var dueAt = schedule.nextRunAt;
		LocalDate end = dueAt.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
		var request = new PlatformReportService.ReportRequest(schedule.reportType, end.minusDays(schedule.periodDays - 1L), end);
		reports.requestScheduled(schedule.createdBy, request, "schedule-" + schedule.id + "-" + dueAt.toEpochMilli(), schedule.id, dueAt);
		schedule.nextRunAt = schedule.cadence.nextAfter(now, schedule.timezone, schedule.localTime);
		schedule.updatedAt = now;
		return true;
	}

	private ReportScheduleEntity current(UUID id, long expectedVersion) {
		var schedule = schedules.lockById(id).filter(value -> !"DELETED".equals(value.status))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));
		if (schedule.version != expectedVersion) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Schedule changed; reload before editing");
		return schedule;
	}

	private void validate(ScheduleRequest request) {
		if (request == null || request.reportType() == null || request.cadence() == null || request.localTime() == null
				|| request.localTime().getSecond() != 0 || request.localTime().getNano() != 0
				|| request.periodDays() < 1 || request.periodDays() > 366
				|| request.enabled() == null || request.timezone() == null || !ZoneId.getAvailableZoneIds().contains(request.timezone())
				|| !"ADMIN".equals(request.recipientGroup()) || !"ADMIN_REPORT_HISTORY".equals(request.deliveryTarget())) {
			throw new InvalidPlatformReportRequestException("Schedule requires a supported report, cadence, IANA timezone, minute time, 1–366 days, and ADMIN report history delivery");
		}
	}

	private ScheduleResponse response(ReportScheduleEntity value) {
		return new ScheduleResponse(value.id, value.reportType, value.cadence, value.timezone, value.localTime,
				value.periodDays, value.recipientGroup, value.deliveryTarget, value.status, value.nextRunAt,
				value.lastFailureCode, value.createdAt, value.updatedAt, value.version);
	}

	public record ScheduleRequest(PlatformReportType reportType, ReportCadence cadence, String timezone,
			LocalTime localTime, int periodDays, String recipientGroup, String deliveryTarget, Boolean enabled) { }
	public record ScheduleResponse(UUID scheduleId, PlatformReportType reportType, ReportCadence cadence,
			String timezone, LocalTime localTime, int periodDays, String recipientGroup, String deliveryTarget,
			String status, Instant nextRunAt, String lastFailureCode, Instant createdAt, Instant updatedAt, long version) { }
}
