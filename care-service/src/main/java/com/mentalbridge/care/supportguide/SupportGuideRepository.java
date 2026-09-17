package com.mentalbridge.care.supportguide;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SupportGuideRepository extends JpaRepository<SupportGuideEntity, UUID> {

	Optional<SupportGuideEntity> findByIdAndUserId(UUID id, UUID userId);

	Optional<SupportGuideEntity> findByUserIdAndSupportEvaluationIdAndGuidePolicyVersion(
			UUID userId, UUID supportEvaluationId, String guidePolicyVersion);

	List<SupportGuideEntity> findByUserIdOrderByGeneratedAtDescIdDesc(UUID userId, Pageable pageable);

	@Query(value = """
			select * from support_guide
			where user_id = :userId
			  and (generated_at, id) < (:beforeTime, :beforeId)
			order by generated_at desc, id desc
			""", nativeQuery = true)
	List<SupportGuideEntity> historyBefore(@Param("userId") UUID userId,
			@Param("beforeTime") Instant beforeTime, @Param("beforeId") UUID beforeId, Pageable pageable);

	@Query(value = """
			select support_guide_id as "guideId", request_hash as "requestHash"
			from support_guide_request where user_id = :userId and idempotency_key = :key
			""", nativeQuery = true)
	Optional<RequestRow> findRequest(@Param("userId") UUID userId, @Param("key") String key);

	@Modifying
	@Query(value = """
			insert into support_guide_request (user_id,idempotency_key,request_hash,support_guide_id,created_at)
			values (:userId,:key,:hash,:guideId,:createdAt)
			""", nativeQuery = true)
	int insertRequest(@Param("userId") UUID userId, @Param("key") String key,
			@Param("hash") String hash, @Param("guideId") UUID guideId, @Param("createdAt") Instant createdAt);

	interface RequestRow {
		UUID getGuideId();
		String getRequestHash();
	}
}
