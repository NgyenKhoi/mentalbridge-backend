package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SupportPlanActivityOccurrenceRepository
		extends JpaRepository<SupportPlanActivityOccurrenceEntity, UUID> {

	List<SupportPlanActivityOccurrenceEntity> findBySupportPlanIdAndUserIdAndLocalDateBetweenOrderByScheduledAtAscIdAsc(
			UUID supportPlanId, UUID userId, LocalDate from, LocalDate through);

	Optional<SupportPlanActivityOccurrenceEntity> findByIdAndUserId(UUID id, UUID userId);

	@Modifying
	@Query(value = """
			insert into support_plan_activity_occurrence
				(id,activity_schedule_id,support_plan_id,user_id,schedule_version,local_date,local_time,
				 timezone,scheduled_at,state,state_reason,source_plan_version,source_slot_key,
				 source_resource_id,source_content_version,source_title,version,created_at,updated_at)
			values
				(:id,:scheduleId,:planId,:userId,:scheduleVersion,:localDate,:localTime,
				 :timezone,:scheduledAt,'SCHEDULED',null,:sourcePlanVersion,:sourceSlotKey,
				 :sourceResourceId,:sourceContentVersion,:sourceTitle,0,:now,:now)
			on conflict (activity_schedule_id,schedule_version,local_date) do nothing
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") UUID id, @Param("scheduleId") UUID scheduleId,
			@Param("planId") UUID planId, @Param("userId") UUID userId,
			@Param("scheduleVersion") int scheduleVersion, @Param("localDate") LocalDate localDate,
			@Param("localTime") LocalTime localTime, @Param("timezone") String timezone,
			@Param("scheduledAt") Instant scheduledAt, @Param("sourcePlanVersion") long sourcePlanVersion,
			@Param("sourceSlotKey") String sourceSlotKey, @Param("sourceResourceId") UUID sourceResourceId,
			@Param("sourceContentVersion") long sourceContentVersion,
			@Param("sourceTitle") String sourceTitle, @Param("now") Instant now);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			update support_plan_activity_occurrence
			set state = 'CANCELLED', state_reason = :reason, cancelled_at = :now,
			    updated_at = :now, version = version + 1
			where support_plan_id = :planId and state = 'SCHEDULED' and scheduled_at >= :now
			""", nativeQuery = true)
	int cancelFuture(@Param("planId") UUID planId, @Param("reason") String reason,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			update support_plan_activity_occurrence
			set state = 'CANCELLED', state_reason = :reason, cancelled_at = :now,
			    updated_at = :now, version = version + 1
			where support_plan_id = :planId and scheduled_at >= :now
			  and (state = 'SCHEDULED' or (state = 'CANCELLED' and state_reason = 'PLAN_PAUSED'))
			""", nativeQuery = true)
	int cancelFutureForTerminal(@Param("planId") UUID planId, @Param("reason") String reason,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			update support_plan_activity_occurrence
			set state = 'SCHEDULED', state_reason = null, cancelled_at = null,
			    updated_at = :now, version = version + 1
			where support_plan_id = :planId and state = 'CANCELLED'
			  and state_reason = 'PLAN_PAUSED' and scheduled_at >= :now
			""", nativeQuery = true)
	int restoreFuturePaused(@Param("planId") UUID planId, @Param("now") Instant now);
}
