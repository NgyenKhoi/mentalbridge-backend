package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SupportPlanRepository extends JpaRepository<SupportPlanEntity, UUID> {

	Optional<SupportPlanEntity> findByUserIdAndStatus(UUID userId, String status);

	Optional<SupportPlanEntity> findByIdAndUserId(UUID id, UUID userId);

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

	interface RequestRow {
		UUID getPlanId();
		String getRequestHash();
	}
}
