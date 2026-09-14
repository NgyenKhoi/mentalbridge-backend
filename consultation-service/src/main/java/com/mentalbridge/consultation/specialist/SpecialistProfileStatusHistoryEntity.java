package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "specialist_profile_status_history")
class SpecialistProfileStatusHistoryEntity {

	@Id
	private UUID id;
	private UUID specialistAccountId;
	@Enumerated(EnumType.STRING)
	private SpecialistApprovalStatus approvalStatus;
	private UUID actorAccountId;
	@Enumerated(EnumType.STRING)
	private ActorRole actorRole;
	private String reasonCode;
	private Instant occurredAt;

	protected SpecialistProfileStatusHistoryEntity() {
	}

	SpecialistProfileStatusHistoryEntity(UUID specialistAccountId, SpecialistApprovalStatus approvalStatus,
			UUID actorAccountId, ActorRole actorRole, Instant occurredAt) {
		this.id = UUID.randomUUID();
		this.specialistAccountId = specialistAccountId;
		this.approvalStatus = approvalStatus;
		this.actorAccountId = actorAccountId;
		this.actorRole = actorRole;
		this.occurredAt = occurredAt;
	}

	enum ActorRole {
		SPECIALIST,
		ADMIN
	}
}
