package com.mentalbridge.identity.registration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

	@Query(value = """
			select * from outbox_event
			where published_at is null
			  and coalesce(next_attempt_at, occurred_at) <= :now
			order by coalesce(next_attempt_at, occurred_at), occurred_at, id
			for update skip locked
			limit :limit
			""", nativeQuery = true)
	List<OutboxEventEntity> findDueForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
