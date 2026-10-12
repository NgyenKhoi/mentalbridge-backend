package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface SupportPlanActivityScheduleRepository extends JpaRepository<SupportPlanActivityScheduleEntity, UUID> {

	List<SupportPlanActivityScheduleEntity> findBySupportPlanIdOrderByOrdinal(UUID supportPlanId);

	List<SupportPlanActivityScheduleEntity> findBySupportPlanIdAndSourceSlotKeyOrderByScheduleVersionDesc(
			UUID supportPlanId, String sourceSlotKey);

	List<SupportPlanActivityScheduleEntity> findBySupportPlanIdAndSourceActionIdOrderByScheduleVersionDesc(
			UUID supportPlanId, UUID sourceActionId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select schedule from SupportPlanActivityScheduleEntity schedule "
			+ "where schedule.supportPlanId = :supportPlanId order by schedule.ordinal")
	List<SupportPlanActivityScheduleEntity> findBySupportPlanIdForUpdate(
			@Param("supportPlanId") UUID supportPlanId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select schedule from SupportPlanActivityScheduleEntity schedule "
			+ "where schedule.sourceActionId = :actionId and schedule.userId = :userId order by schedule.ordinal")
	List<SupportPlanActivityScheduleEntity> findBySourceActionIdAndUserIdForUpdate(
			@Param("actionId") UUID actionId, @Param("userId") UUID userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select schedule from SupportPlanActivityScheduleEntity schedule "
			+ "where schedule.supportPlanId = :supportPlanId and schedule.sourceSlotKey = :sourceSlotKey "
			+ "order by schedule.scheduleVersion desc")
	List<SupportPlanActivityScheduleEntity> findBySupportPlanIdAndSourceSlotKeyForUpdate(
			@Param("supportPlanId") UUID supportPlanId, @Param("sourceSlotKey") String sourceSlotKey);

	default Optional<SupportPlanActivityScheduleEntity> currentForSlot(UUID supportPlanId, String sourceSlotKey) {
		return findBySupportPlanIdAndSourceSlotKeyOrderByScheduleVersionDesc(supportPlanId, sourceSlotKey)
				.stream().filter(value -> !"ENDED".equals(value.status())).findFirst();
	}
}
