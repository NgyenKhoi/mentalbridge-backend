package com.mentalbridge.community.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityInteractionOutboxEventRepository
		extends JpaRepository<CommunityInteractionOutboxEventEntity, UUID> {

	boolean existsByDeduplicationKey(String deduplicationKey);

	@Query(value = """
			select * from community_interaction_outbox
			where published_at is null
			  and coalesce(next_attempt_at, occurred_at) <= :now
			order by coalesce(next_attempt_at, occurred_at), occurred_at, id
			for update skip locked
			limit :limit
			""", nativeQuery = true)
	List<CommunityInteractionOutboxEventEntity> findDueForUpdate(@Param("now") Instant now,
			@Param("limit") int limit);
}
