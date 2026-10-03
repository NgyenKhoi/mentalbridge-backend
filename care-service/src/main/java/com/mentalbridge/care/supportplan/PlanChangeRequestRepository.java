package com.mentalbridge.care.supportplan;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface PlanChangeRequestRepository extends JpaRepository<PlanChangeRequestEntity, UUID> {

	Optional<PlanChangeRequestEntity> findByUserIdAndId(UUID userId, UUID id);
	Optional<PlanChangeRequestEntity> findByUserIdAndSourceProposalId(UUID userId, UUID sourceProposalId);
	Optional<PlanChangeRequestEntity> findBySpecialistIdAndSourceProposalId(UUID specialistId, UUID sourceProposalId);
	Optional<PlanChangeRequestEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
	Optional<PlanChangeRequestEntity> findByUserIdAndDecisionIdempotencyKey(UUID userId,
			String decisionIdempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from PlanChangeRequestEntity request where request.id=:id and request.userId=:userId")
	Optional<PlanChangeRequestEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id,
			@Param("userId") UUID userId);
}
