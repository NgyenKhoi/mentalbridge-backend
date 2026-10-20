package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.repository.query.Param;

interface ChatOutboxRepository extends JpaRepository<ChatOutboxEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from ChatOutboxEntity event where event.id = :id")
    Optional<ChatOutboxEntity> lock(@Param("id") UUID id);
    @Query(value = """
            select * from community_chat_outbox where published_at is null
            and (next_attempt_at is null or next_attempt_at <= :now)
            order by occurred_at, id limit :batch for update skip locked
            """, nativeQuery = true)
    List<ChatOutboxEntity> due(@Param("now") Instant now, @Param("batch") int batch);
}
