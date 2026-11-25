package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Immutable
@Entity
@Table(name = "specialist_profile_amendment_history")
class ProfileAmendmentHistoryEntity {

	@Id private UUID id;
	private UUID amendmentId;
	private long amendmentVersion;
	@Enumerated(EnumType.STRING) private ProfileAmendmentEntity.Status status;
	@JdbcTypeCode(SqlTypes.JSON) private SpecialistProfileService.ProfileCommand proposedProfile;
	private UUID actorAccountId;
	private String actorRole;
	@Enumerated(EnumType.STRING) private SpecialistDecisionReasonCode reasonCode;
	private Instant occurredAt;

	protected ProfileAmendmentHistoryEntity() {
	}

	ProfileAmendmentHistoryEntity(ProfileAmendmentEntity amendment, UUID actor, String role) {
		id = UUID.randomUUID();
		amendmentId = amendment.id();
		amendmentVersion = amendment.version();
		status = amendment.status();
		proposedProfile = amendment.proposedProfile();
		actorAccountId = actor;
		actorRole = role;
		reasonCode = amendment.reasonCode();
		occurredAt = amendment.updatedAt();
	}
}
