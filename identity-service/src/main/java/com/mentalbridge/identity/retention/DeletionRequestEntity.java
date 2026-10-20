package com.mentalbridge.identity.retention;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "deletion_request")
public class DeletionRequestEntity {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "subject_reference_hash", nullable = false, length = 64)
	private String subjectReferenceHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private DeletionRequestStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "hold_type", nullable = false, length = 32)
	private DeletionHoldType holdType;

	@Column(name = "hold_reason", length = 64)
	private String holdReason;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "max_retries", nullable = false)
	private int maxRetries;

	@Enumerated(EnumType.STRING)
	@Column(name = "escalation_state", nullable = false, length = 32)
	private DeletionEscalationState escalationState;

	@Column(name = "failure_code", length = 64)
	private String failureCode;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt;

	@Column(name = "scheduled_execution_at", nullable = false)
	private Instant scheduledExecutionAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "last_attempt_at")
	private Instant lastAttemptAt;

	@Version
	@Column(nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@OneToMany(mappedBy = "deletionRequest", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
	private List<DeletionOwnerFactEntity> ownerFacts = new ArrayList<>();

	@Enumerated(EnumType.STRING)
	@Column(name = "previous_status", length = 32)
	private DeletionRequestStatus previousStatus;

	protected DeletionRequestEntity() {
	}

	public DeletionRequestEntity(UUID id, UUID accountId, String subjectReferenceHash, DeletionRequestStatus status,
			DeletionHoldType holdType, String holdReason, int retryCount, int maxRetries,
			DeletionEscalationState escalationState, String failureCode, Instant requestedAt,
			Instant scheduledExecutionAt, Instant completedAt, Instant lastAttemptAt, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.accountId = accountId;
		this.subjectReferenceHash = subjectReferenceHash;
		this.status = status;
		this.holdType = holdType;
		this.holdReason = holdReason;
		this.retryCount = retryCount;
		this.maxRetries = maxRetries;
		this.escalationState = escalationState;
		this.failureCode = failureCode;
		this.requestedAt = requestedAt;
		this.scheduledExecutionAt = scheduledExecutionAt;
		this.completedAt = completedAt;
		this.lastAttemptAt = lastAttemptAt;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public UUID id() { return id; }
	public UUID accountId() { return accountId; }
	public String subjectReferenceHash() { return subjectReferenceHash; }
	public DeletionRequestStatus status() { return status; }
	public DeletionHoldType holdType() { return holdType; }
	public String holdReason() { return holdReason; }
	public int retryCount() { return retryCount; }
	public int maxRetries() { return maxRetries; }
	public DeletionEscalationState escalationState() { return escalationState; }
	public String failureCode() { return failureCode; }
	public Instant requestedAt() { return requestedAt; }
	public Instant scheduledExecutionAt() { return scheduledExecutionAt; }
	public Instant completedAt() { return completedAt; }
	public Instant lastAttemptAt() { return lastAttemptAt; }
	public long version() { return version; }
	public Instant createdAt() { return createdAt; }
	public Instant updatedAt() { return updatedAt; }
	public List<DeletionOwnerFactEntity> ownerFacts() { return ownerFacts; }

	public void addOwnerFact(DeletionOwnerFactEntity fact) {
		fact.setDeletionRequest(this);
		this.ownerFacts.add(fact);
	}

	public void applyHold(DeletionHoldType holdType, String holdReason, Instant now) {
		this.holdType = holdType;
		this.holdReason = holdReason;
		if (this.status != DeletionRequestStatus.HELD) {
			this.previousStatus = this.status;
		}
		this.status = DeletionRequestStatus.HELD;
		this.updatedAt = now;
	}

	public void releaseHold(Instant now) {
		this.holdType = DeletionHoldType.NONE;
		this.holdReason = null;
		if (this.previousStatus != null) {
			this.status = this.previousStatus;
		} else if (this.failureCode != null || (this.retryCount >= this.maxRetries && this.maxRetries > 0)) {
			this.status = DeletionRequestStatus.FAILED;
		} else if (this.retryCount > 0) {
			this.status = DeletionRequestStatus.IN_PROGRESS;
		} else {
			this.status = DeletionRequestStatus.PENDING;
		}
		this.previousStatus = null;
		this.updatedAt = now;
	}

	public void markInProgress(Instant now) {
		this.status = DeletionRequestStatus.IN_PROGRESS;
		this.updatedAt = now;
	}

	public void recordAttempt(Instant now) {
		this.retryCount++;
		this.lastAttemptAt = now;
		this.updatedAt = now;
	}

	public void complete(Instant now) {
		this.status = DeletionRequestStatus.COMPLETED;
		this.completedAt = now;
		this.failureCode = null;
		this.escalationState = DeletionEscalationState.NONE;
		this.updatedAt = now;
	}

	public void fail(String failureCode, Instant now) {
		this.status = DeletionRequestStatus.FAILED;
		this.failureCode = failureCode;
		if (this.retryCount >= this.maxRetries && this.maxRetries > 0) {
			this.escalationState = DeletionEscalationState.MANUAL_ESCALATION_REQUIRED;
		}
		this.updatedAt = now;
	}
}

