package com.mentalbridge.identity.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "platform_report_job")
public class PlatformReportJobEntity {

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "report_type", nullable = false, updatable = false, length = 64)
	private PlatformReportType reportType;

	@Column(name = "scope_version", nullable = false, updatable = false, length = 64)
	private String scopeVersion;

	@Column(name = "period_start", nullable = false, updatable = false)
	private LocalDate periodStart;

	@Column(name = "period_end", nullable = false, updatable = false)
	private LocalDate periodEnd;

	@Column(name = "requested_by", nullable = false, updatable = false)
	private UUID requestedBy;

	@Column(name = "requested_at", nullable = false, updatable = false)
	private Instant requestedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private PlatformReportStatus status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "source_versions", nullable = false, updatable = false, columnDefinition = "jsonb")
	private Map<String, String> sourceVersions;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false, updatable = false, length = 64)
	private String requestHash;

	@Column(name = "retry_of", updatable = false)
	private UUID retryOf;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "failed_at")
	private Instant failedAt;

	@Column(name = "failure_code", length = 64)
	private String failureCode;

	@Column(name = "schedule_id", updatable = false)
	private UUID scheduleId;

	@Column(name = "scheduled_for", updatable = false)
	private Instant scheduledFor;

	void schedule(UUID scheduleId, Instant scheduledFor) {
		this.scheduleId = scheduleId;
		this.scheduledFor = scheduledFor;
	}

	protected PlatformReportJobEntity() {
	}

	static PlatformReportJobEntity queued(PlatformReportType type, LocalDate periodStart, LocalDate periodEnd,
			UUID requestedBy, Instant requestedAt, String idempotencyKey, String requestHash, UUID retryOf) {
		var job = new PlatformReportJobEntity();
		job.id = UUID.randomUUID();
		job.reportType = type;
		job.scopeVersion = "platform-account-activity-report-v1";
		job.periodStart = periodStart;
		job.periodEnd = periodEnd;
		job.requestedBy = requestedBy;
		job.requestedAt = requestedAt;
		job.status = PlatformReportStatus.QUEUED;
		job.sourceVersions = new LinkedHashMap<>(Map.of("identityAccounts", "identity-account-projection-v1"));
		job.idempotencyKey = idempotencyKey;
		job.requestHash = requestHash;
		job.retryOf = retryOf;
		return job;
	}

	void start(Instant now) {
		status = PlatformReportStatus.RUNNING;
		startedAt = now;
	}

	void complete(Instant now) {
		status = PlatformReportStatus.COMPLETED;
		completedAt = now;
	}

	void fail(Instant now, String code) {
		status = PlatformReportStatus.FAILED;
		failedAt = now;
		failureCode = code;
	}

	void stale(Instant now, String code) {
		status = PlatformReportStatus.STALE;
		failedAt = now;
		failureCode = code;
	}

	public UUID id() { return id; }
	public PlatformReportType reportType() { return reportType; }
	public String scopeVersion() { return scopeVersion; }
	public LocalDate periodStart() { return periodStart; }
	public LocalDate periodEnd() { return periodEnd; }
	public UUID requestedBy() { return requestedBy; }
	public Instant requestedAt() { return requestedAt; }
	public PlatformReportStatus status() { return status; }
	public Map<String, String> sourceVersions() { return Map.copyOf(sourceVersions); }
	public String requestHash() { return requestHash; }
	public UUID retryOf() { return retryOf; }
	public Instant startedAt() { return startedAt; }
	public Instant completedAt() { return completedAt; }
	public Instant failedAt() { return failedAt; }
	public String failureCode() { return failureCode; }

}
