package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface SupportPlanActivityOccurrenceRepository
		extends JpaRepository<SupportPlanActivityOccurrenceEntity, UUID> {

	List<SupportPlanActivityOccurrenceEntity> findBySupportPlanIdAndUserIdAndLocalDateBetweenOrderByScheduledAtAscIdAsc(
			UUID supportPlanId, UUID userId, LocalDate from, LocalDate through);

	Optional<SupportPlanActivityOccurrenceEntity> findByIdAndUserId(UUID id, UUID userId);

	@Query(value = """
			select id
			from support_plan_activity_occurrence
			where evidence_owner = :owner and evidence_kind = :kind and evidence_id = :factId
			  and evidence_revision is not distinct from :factRevision
			""", nativeQuery = true)
	Optional<UUID> findOccurrenceIdByEvidence(@Param("owner") String owner, @Param("kind") String kind,
			@Param("factId") String factId, @Param("factRevision") String factRevision);

	@Query("select occurrence.supportPlanId from SupportPlanActivityOccurrenceEntity occurrence where occurrence.id = :id and occurrence.userId = :userId")
	Optional<UUID> findSupportPlanIdByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

	@Query("""
			select occurrence.supportPlanId as supportPlanId,
			       occurrence.sourceActionId as sourceActionId
			from SupportPlanActivityOccurrenceEntity occurrence
			where occurrence.id = :id and occurrence.userId = :userId
			""")
	Optional<OccurrenceAuthorityLink> findAuthorityLinkByIdAndUserId(@Param("id") UUID id,
			@Param("userId") UUID userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select occurrence from SupportPlanActivityOccurrenceEntity occurrence where occurrence.id = :id and occurrence.userId = :userId")
	Optional<SupportPlanActivityOccurrenceEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id,
			@Param("userId") UUID userId);

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

	@Modifying
	@Query(value = """
			insert into support_plan_activity_occurrence
				(id,activity_schedule_id,support_plan_id,user_id,schedule_version,local_date,local_time,
				 timezone,scheduled_at,state,state_reason,source_plan_version,source_action_id,
				 source_action_version,source_action_type,source_title,version,created_at,updated_at)
			values
				(:id,:scheduleId,:planId,:userId,:scheduleVersion,:localDate,:localTime,
				 :timezone,:scheduledAt,'SCHEDULED',null,:sourcePlanVersion,:sourceActionId,
				 :sourceActionVersion,:sourceActionType,:sourceTitle,0,:now,:now)
			on conflict (activity_schedule_id,schedule_version,local_date) do nothing
			""", nativeQuery = true)
	int insertNativeIfAbsent(@Param("id") UUID id, @Param("scheduleId") UUID scheduleId,
			@Param("planId") UUID planId, @Param("userId") UUID userId,
			@Param("scheduleVersion") int scheduleVersion, @Param("localDate") LocalDate localDate,
			@Param("localTime") LocalTime localTime, @Param("timezone") String timezone,
			@Param("scheduledAt") Instant scheduledAt, @Param("sourcePlanVersion") long sourcePlanVersion,
			@Param("sourceActionId") UUID sourceActionId,
			@Param("sourceActionVersion") long sourceActionVersion,
			@Param("sourceActionType") String sourceActionType,
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
			set state = 'CANCELLED', state_reason = 'ACTION_ENDED', cancelled_at = :now,
			    updated_at = :now, version = version + 1
			where source_action_id = :actionId and user_id = :userId and scheduled_at >= :now
			  and (state = 'SCHEDULED' or (state = 'CANCELLED' and state_reason = 'PLAN_PAUSED'))
			""", nativeQuery = true)
	int cancelFutureForEndedAction(@Param("actionId") UUID actionId, @Param("userId") UUID userId,
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

	@Modifying
	@Query(value = """
			insert into outbox_event (id,message_type,schema_version,aggregate_type,aggregate_id,
				aggregate_version,correlation_id,payload,occurred_at,created_at)
			values (:eventId,'care.support-plan.engagement-changed','1.0','SUPPORT_PLAN_OCCURRENCE',
				:aggregateId,:aggregateVersion,:correlationId,cast(:payload as jsonb),:occurredAt,:occurredAt)
			""", nativeQuery = true)
	int insertEngagementOutbox(@Param("eventId") UUID eventId, @Param("aggregateId") UUID aggregateId,
			@Param("aggregateVersion") long aggregateVersion, @Param("correlationId") UUID correlationId,
			@Param("payload") String payload, @Param("occurredAt") Instant occurredAt);

	@Modifying
	@Query(value = """
			insert into outbox_event (id,message_type,schema_version,aggregate_type,aggregate_id,
				aggregate_version,correlation_id,payload,occurred_at,attempt_count,created_at)
			values (:eventId,'care.support-plan.action-occurrence-changed','2.0',
				'SUPPORT_PLAN_ACTION_OCCURRENCE',:aggregateId,:aggregateVersion,:correlationId,
				cast(:payload as jsonb),:occurredAt,0,:occurredAt)
			""", nativeQuery = true)
	int insertActionOccurrenceOutbox(@Param("eventId") UUID eventId,
			@Param("aggregateId") UUID aggregateId, @Param("aggregateVersion") long aggregateVersion,
			@Param("correlationId") UUID correlationId, @Param("payload") String payload,
			@Param("occurredAt") Instant occurredAt);

	interface OccurrenceAuthorityLink {
		UUID getSupportPlanId();
		UUID getSourceActionId();
	}
}
