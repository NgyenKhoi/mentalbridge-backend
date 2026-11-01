package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

interface ChatRequestRepository extends JpaRepository<ChatRequestEntity, UUID> {
    @Query(value = """
            select r.* from community_chat_request r
            join community_profile a on a.id = r.requester_profile_id join community_profile b on b.id = r.recipient_profile_id
            where r.recipient_profile_id = :viewer and r.state = 'PENDING' and r.expires_at > :now
            and a.status = 'ACTIVE' and b.status = 'ACTIVE'
            and (r.created_at < :before or (r.created_at = :before and r.id < :beforeId))
            and not exists (select 1 from community_block x where
              (x.blocker_profile_id = a.id and x.blocked_profile_id = b.id) or (x.blocker_profile_id = b.id and x.blocked_profile_id = a.id))
            and not exists (select 1 from community_access_restriction x where x.profile_id in (a.id, b.id) and x.lifted_at is null)
            order by r.created_at desc, r.id desc limit :batch
            """, nativeQuery = true)
    List<ChatRequestEntity> candidates(@Param("viewer") UUID viewer, @Param("now") Instant now,
            @Param("before") Instant before, @Param("beforeId") UUID beforeId, @Param("batch") int batch);
    List<ChatRequestEntity> findByConversationIdAndState(UUID conversationId, String state);
    boolean existsByConversationIdAndRequesterProfileIdAndStateIn(UUID conversation, UUID requester, List<String> states);
    long countByRequesterProfileIdAndCreatedAtAfter(UUID requester, Instant since);
    @Query("""
            select request from ChatRequestEntity request where request.recipientProfileId = :viewer
            and request.state = 'PENDING' and request.expiresAt > :now order by request.createdAt desc, request.id desc
            """)
    List<ChatRequestEntity> incoming(@Param("viewer") UUID viewer, @Param("now") Instant now, Pageable page);
}
