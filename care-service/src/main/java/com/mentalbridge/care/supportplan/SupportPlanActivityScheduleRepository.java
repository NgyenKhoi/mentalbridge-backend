package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface SupportPlanActivityScheduleRepository extends JpaRepository<SupportPlanActivityScheduleEntity, UUID> {

	List<SupportPlanActivityScheduleEntity> findBySupportPlanIdOrderByOrdinal(UUID supportPlanId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select schedule from SupportPlanActivityScheduleEntity schedule "
			+ "where schedule.supportPlanId = :supportPlanId order by schedule.ordinal")
	List<SupportPlanActivityScheduleEntity> findBySupportPlanIdForUpdate(
			@Param("supportPlanId") UUID supportPlanId);
}
