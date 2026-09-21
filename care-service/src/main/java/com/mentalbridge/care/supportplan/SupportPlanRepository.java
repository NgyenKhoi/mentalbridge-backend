package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface SupportPlanRepository extends JpaRepository<SupportPlanEntity, UUID> {

	Optional<SupportPlanEntity> findByUserIdAndStatus(UUID userId, String status);

	Optional<SupportPlanEntity> findByIdAndUserId(UUID id, UUID userId);

	@Query(value = """
			select * from support_plan
			where user_id = :userId
			  and status in ('COMPLETED','SUPERSEDED','DISCARDED')
			  and (
			    cast(:beforeTime as timestamptz) is null
			    or updated_at < :beforeTime
			    or (updated_at = :beforeTime and id < :beforeId)
			  )
			order by updated_at desc, id desc
			limit :limit
			""", nativeQuery = true)
	List<SupportPlanEntity> findTerminalHistory(@Param("userId") UUID userId,
			@Param("beforeTime") Instant beforeTime, @Param("beforeId") UUID beforeId,
			@Param("limit") int limit);

	Optional<SupportPlanEntity> findByUserIdAndStatusIn(UUID userId, java.util.Collection<String> statuses);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select plan from SupportPlanEntity plan where plan.userId = :userId and plan.status in :statuses")
	Optional<SupportPlanEntity> findByUserIdAndStatusInForUpdate(@Param("userId") UUID userId,
			@Param("statuses") java.util.Collection<String> statuses);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select plan from SupportPlanEntity plan where plan.id = :id and plan.userId = :userId")
	Optional<SupportPlanEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

	@Query(value = """
			select support_plan_id as "planId", request_hash as "requestHash"
			from support_plan_request
			where user_id = :userId and idempotency_key = :idempotencyKey
			""", nativeQuery = true)
	Optional<RequestRow> findRequest(@Param("userId") UUID userId, @Param("idempotencyKey") String idempotencyKey);

	@Modifying
	@Query(value = """
			insert into support_plan_request (user_id,idempotency_key,request_hash,support_plan_id,created_at)
			values (:userId,:idempotencyKey,:requestHash,:planId,:createdAt)
			""", nativeQuery = true)
	int insertRequest(@Param("userId") UUID userId, @Param("idempotencyKey") String idempotencyKey,
			@Param("requestHash") String requestHash, @Param("planId") UUID planId,
			@Param("createdAt") Instant createdAt);

	@Query(value = """
			select command_type as "commandType", request_hash as "requestHash",
			       support_plan_id as "planId", expected_version as "expectedVersion",
			       resulting_version as "resultingVersion", resulting_status as "resultingStatus",
			       resulting_updated_at as "resultingUpdatedAt"
			from support_plan_command
			where user_id = :userId and idempotency_key = :idempotencyKey
			""", nativeQuery = true)
	Optional<CommandRow> findCommand(@Param("userId") UUID userId,
			@Param("idempotencyKey") String idempotencyKey);

	@Modifying
	@Query(value = """
			insert into support_plan_command
				(user_id,idempotency_key,command_type,request_hash,support_plan_id,expected_version,
				 resulting_version,resulting_status,resulting_updated_at,evaluation_policy_version,
				 entitlement_package,entitlement_source,entitlement_policy_version,entitlement_version,
				 entitlement_decided_at,resource_policy_version,resources_resolved_at,created_at)
			values (:userId,:idempotencyKey,:commandType,:requestHash,:planId,:expectedVersion,
				:resultingVersion,:resultingStatus,:resultingUpdatedAt,:evaluationPolicyVersion,
				:entitlementPackage,:entitlementSource,:entitlementPolicyVersion,:entitlementVersion,
				:entitlementDecidedAt,:resourcePolicyVersion,:resourcesResolvedAt,:createdAt)
			""", nativeQuery = true)
	int insertCommand(@Param("userId") UUID userId, @Param("idempotencyKey") String idempotencyKey,
			@Param("commandType") String commandType, @Param("requestHash") String requestHash,
			@Param("planId") UUID planId, @Param("expectedVersion") long expectedVersion,
			@Param("resultingVersion") long resultingVersion, @Param("resultingStatus") String resultingStatus,
			@Param("resultingUpdatedAt") Instant resultingUpdatedAt,
			@Param("evaluationPolicyVersion") String evaluationPolicyVersion,
			@Param("entitlementPackage") String entitlementPackage,
			@Param("entitlementSource") String entitlementSource,
			@Param("entitlementPolicyVersion") String entitlementPolicyVersion,
			@Param("entitlementVersion") long entitlementVersion,
			@Param("entitlementDecidedAt") Instant entitlementDecidedAt,
			@Param("resourcePolicyVersion") String resourcePolicyVersion,
			@Param("resourcesResolvedAt") Instant resourcesResolvedAt,
			@Param("createdAt") Instant createdAt);

	@Modifying
	@Query(value = """
			insert into support_plan_command_selection
				(user_id,idempotency_key,ordinal,slot_key,resource_id,content_version)
			values (:userId,:idempotencyKey,:ordinal,:slotKey,:resourceId,:contentVersion)
			""", nativeQuery = true)
	int insertCommandSelection(@Param("userId") UUID userId, @Param("idempotencyKey") String idempotencyKey,
			@Param("ordinal") int ordinal, @Param("slotKey") String slotKey,
			@Param("resourceId") UUID resourceId, @Param("contentVersion") long contentVersion);

	@Query(value = """
			select slot_key as "slotKey", resource_id as "resourceId", content_version as "contentVersion"
			from support_plan_command_selection
			where user_id = :userId and idempotency_key = :idempotencyKey
			order by ordinal
			""", nativeQuery = true)
	java.util.List<CommandSelectionRow> findCommandSelections(@Param("userId") UUID userId,
			@Param("idempotencyKey") String idempotencyKey);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			insert into outbox_event (id,message_type,schema_version,aggregate_type,aggregate_id,
				aggregate_version,correlation_id,payload,occurred_at,attempt_count,created_at)
			values (:eventId,'care.support-plan.activated','1.0','SUPPORT_PLAN',:aggregateId,
				:aggregateVersion,:correlationId,cast(:payload as jsonb),:occurredAt,0,:occurredAt)
			""", nativeQuery = true)
	int insertActivationOutbox(@Param("eventId") UUID eventId, @Param("aggregateId") UUID aggregateId,
			@Param("aggregateVersion") long aggregateVersion, @Param("correlationId") UUID correlationId,
			@Param("payload") String payload, @Param("occurredAt") Instant occurredAt);

	interface RequestRow {
		UUID getPlanId();
		String getRequestHash();
	}

	interface CommandRow {
		String getCommandType();
		String getRequestHash();
		UUID getPlanId();
		long getExpectedVersion();
		long getResultingVersion();
		String getResultingStatus();
		Instant getResultingUpdatedAt();
	}

	interface CommandSelectionRow {
		String getSlotKey();
		UUID getResourceId();
		long getContentVersion();
	}
}
