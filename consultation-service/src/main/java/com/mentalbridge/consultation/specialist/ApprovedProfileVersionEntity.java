package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Immutable
@Entity
@Table(name = "specialist_profile_approved_version")
class ApprovedProfileVersionEntity {

	@Id private UUID id;
	private UUID specialistAccountId;
	private long publishedVersion;
	@JdbcTypeCode(SqlTypes.JSON) private SpecialistProfileService.ProfileCommand profileSnapshot;
	private UUID approvedBy;
	private Instant approvedAt;
	private UUID sourceAmendmentId;

	protected ApprovedProfileVersionEntity() {
	}

	ApprovedProfileVersionEntity(SpecialistProfileEntity profile, UUID sourceAmendmentId) {
		id = UUID.randomUUID();
		specialistAccountId = profile.accountId();
		publishedVersion = profile.publishedVersion();
		profileSnapshot = profile.command();
		approvedBy = profile.reviewedBy();
		approvedAt = profile.reviewedAt();
		this.sourceAmendmentId = sourceAmendmentId;
	}
}
