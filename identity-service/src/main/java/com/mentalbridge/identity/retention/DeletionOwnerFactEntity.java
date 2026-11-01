package com.mentalbridge.identity.retention;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "deletion_owner_fact")
public class DeletionOwnerFactEntity {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "deletion_request_id", nullable = false)
	private DeletionRequestEntity deletionRequest;

	@Enumerated(EnumType.STRING)
	@Column(name = "owner_service", nullable = false, length = 32)
	private RetentionOwnerService ownerService;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private DeletionFactStatus status;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "evidence_reference", length = 128)
	private String evidenceReference;

	@Column(name = "failure_reason", length = 64)
	private String failureReason;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected DeletionOwnerFactEntity() {
	}

	public DeletionOwnerFactEntity(UUID id, DeletionRequestEntity deletionRequest, RetentionOwnerService ownerService,
			DeletionFactStatus status, Instant completedAt, String evidenceReference, String failureReason, Instant updatedAt) {
		this.id = id;
		this.deletionRequest = deletionRequest;
		this.ownerService = ownerService;
		this.status = status;
		this.completedAt = completedAt;
		this.evidenceReference = evidenceReference;
		this.failureReason = failureReason;
		this.updatedAt = updatedAt;
	}

	public UUID id() { return id; }
	public DeletionRequestEntity deletionRequest() { return deletionRequest; }
	public RetentionOwnerService ownerService() { return ownerService; }
	public DeletionFactStatus status() { return status; }
	public Instant completedAt() { return completedAt; }
	public String evidenceReference() { return evidenceReference; }
	public String failureReason() { return failureReason; }
	public Instant updatedAt() { return updatedAt; }

	public void markCompleted(Instant now, String evidenceReference) {
		this.status = DeletionFactStatus.COMPLETED;
		this.completedAt = now;
		this.evidenceReference = evidenceReference;
		this.failureReason = null;
		this.updatedAt = now;
	}

	public void markFailed(Instant now, String failureReason) {
		this.status = DeletionFactStatus.FAILED;
		this.failureReason = failureReason;
		this.updatedAt = now;
	}

	public void markHeld(Instant now, String holdReason) {
		this.status = DeletionFactStatus.HELD;
		this.failureReason = holdReason;
		this.updatedAt = now;
	}

	public void setDeletionRequest(DeletionRequestEntity deletionRequest) {
		this.deletionRequest = deletionRequest;
	}
}

