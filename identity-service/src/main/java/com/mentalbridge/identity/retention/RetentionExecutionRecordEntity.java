package com.mentalbridge.identity.retention;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "retention_execution_record")
public class RetentionExecutionRecordEntity {

	@Id
	private UUID id;

	@Column(name = "policy_id", nullable = false, length = 64)
	private String policyId;

	@Column(name = "owner_service", nullable = false, length = 32)
	private String ownerService;

	@Column(name = "job_name", nullable = false, length = 64)
	private String jobName;

	@Column(name = "job_status", nullable = false, length = 32)
	private String jobStatus;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "items_evaluated", nullable = false)
	private int itemsEvaluated;

	@Column(name = "records_purged", nullable = false)
	private int recordsPurged;

	@Column(name = "tombstones_created", nullable = false)
	private int tombstonesCreated;

	@Column(name = "holds_honored", nullable = false)
	private int holdsHonored;

	@Column(name = "failure_code", length = 64)
	private String failureCode;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected RetentionExecutionRecordEntity() {
	}

	public RetentionExecutionRecordEntity(UUID id, String policyId, String ownerService, String jobName,
			String jobStatus, Instant startedAt, Instant completedAt, int itemsEvaluated, int recordsPurged,
			int tombstonesCreated, int holdsHonored, String failureCode, Instant createdAt) {
		this.id = id;
		this.policyId = policyId;
		this.ownerService = ownerService;
		this.jobName = jobName;
		this.jobStatus = jobStatus;
		this.startedAt = startedAt;
		this.completedAt = completedAt;
		this.itemsEvaluated = itemsEvaluated;
		this.recordsPurged = recordsPurged;
		this.tombstonesCreated = tombstonesCreated;
		this.holdsHonored = holdsHonored;
		this.failureCode = failureCode;
		this.createdAt = createdAt;
	}

	public UUID id() { return id; }
	public String policyId() { return policyId; }
	public String ownerService() { return ownerService; }
	public String jobName() { return jobName; }
	public String jobStatus() { return jobStatus; }
	public Instant startedAt() { return startedAt; }
	public Instant completedAt() { return completedAt; }
	public int itemsEvaluated() { return itemsEvaluated; }
	public int recordsPurged() { return recordsPurged; }
	public int tombstonesCreated() { return tombstonesCreated; }
	public int holdsHonored() { return holdsHonored; }
	public String failureCode() { return failureCode; }
	public Instant createdAt() { return createdAt; }
}

