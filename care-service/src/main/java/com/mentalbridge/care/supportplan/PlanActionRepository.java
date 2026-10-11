package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface PlanActionRepository extends JpaRepository<PlanActionEntity, UUID> {

	List<PlanActionEntity> findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(
			UUID supportPlanId, UUID userId);

	@Query("select action.supportPlanId from PlanActionEntity action where action.id = :id and action.userId = :userId")
	Optional<UUID> findSupportPlanIdByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select action from PlanActionEntity action where action.id = :id and action.userId = :userId")
	Optional<PlanActionEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id,
			@Param("userId") UUID userId);
}
