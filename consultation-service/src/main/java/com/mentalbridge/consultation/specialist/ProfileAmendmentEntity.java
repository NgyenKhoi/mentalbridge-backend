package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "specialist_profile_amendment")
class ProfileAmendmentEntity {

	@Id private UUID id;
	private UUID specialistAccountId;
	private long basePublishedVersion;
	@Enumerated(EnumType.STRING) private Status status;
	@JdbcTypeCode(SqlTypes.JSON) private SpecialistProfileService.ProfileCommand proposedProfile;
	private Instant submittedAt;
	private Instant reviewedAt;
	private UUID reviewedBy;
	@Enumerated(EnumType.STRING) private SpecialistDecisionReasonCode reasonCode;
	private Instant createdAt;
	private Instant updatedAt;
	@Version private long version;

	protected ProfileAmendmentEntity() {
	}

	ProfileAmendmentEntity(SpecialistProfileEntity profile, Instant now) {
		id = UUID.randomUUID();
		specialistAccountId = profile.accountId();
		basePublishedVersion = profile.publishedVersion();
		proposedProfile = profile.command();
		status = Status.DRAFT;
		createdAt = now;
		updatedAt = now;
	}

	void edit(SpecialistProfileService.ProfileCommand proposed, Instant now) {
		proposedProfile = proposed;
		if (status == Status.PENDING_REVIEW) {
			status = Status.DRAFT;
			submittedAt = null;
		}
		updatedAt = now;
	}

	void submit(Instant now) {
		status = Status.PENDING_REVIEW;
		submittedAt = now;
		reviewedAt = null;
		reviewedBy = null;
		reasonCode = null;
		updatedAt = now;
	}

	void decide(Status decision, UUID actor, SpecialistDecisionReasonCode reason, Instant now) {
		status = decision;
		reviewedBy = actor;
		reviewedAt = now;
		reasonCode = reason;
		updatedAt = now;
	}

	void cancel(Instant now) {
		status = Status.CANCELLED;
		submittedAt = null;
		reviewedAt = null;
		reviewedBy = null;
		reasonCode = null;
		updatedAt = now;
	}

	UUID id() { return id; }
	UUID specialistAccountId() { return specialistAccountId; }
	long basePublishedVersion() { return basePublishedVersion; }
	Status status() { return status; }
	SpecialistProfileService.ProfileCommand proposedProfile() { return proposedProfile; }
	Instant submittedAt() { return submittedAt; }
	Instant reviewedAt() { return reviewedAt; }
	UUID reviewedBy() { return reviewedBy; }
	SpecialistDecisionReasonCode reasonCode() { return reasonCode; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }
	long version() { return version; }

	enum Status { DRAFT, PENDING_REVIEW, REJECTED, APPROVED, CANCELLED }
}
