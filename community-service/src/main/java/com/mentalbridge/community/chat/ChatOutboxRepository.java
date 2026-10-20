package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ChatOutboxRepository extends JpaRepository<ChatOutboxEntity, UUID> {
    @Query(value = """
            select * from community_chat_outbox where published_at is null
            and (next_attempt_at is null or next_attempt_at <= :now)
            order by occurred_at, id limit :batch for update skip locked
            """, nativeQuery = true)
    List<ChatOutboxEntity> due(@Param("now") Instant now, @Param("batch") int batch);
}
