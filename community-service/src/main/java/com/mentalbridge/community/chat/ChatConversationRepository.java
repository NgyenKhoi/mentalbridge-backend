package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

interface ChatConversationRepository extends JpaRepository<ChatConversationEntity, UUID> {
    @Query(value = """
            select c.* from community_chat_conversation c
            join community_profile a on a.id = c.first_profile_id join community_profile b on b.id = c.second_profile_id
            where (a.id = :viewer or b.id = :viewer) and c.state = 'ACTIVE' and a.status = 'ACTIVE' and b.status = 'ACTIVE'
            and (c.activated_at < :before or (c.activated_at = :before and c.id < :beforeId))
            and not exists (select 1 from community_block x where
              (x.blocker_profile_id = a.id and x.blocked_profile_id = b.id) or (x.blocker_profile_id = b.id and x.blocked_profile_id = a.id))
            and not exists (select 1 from community_access_restriction x where x.profile_id in (a.id, b.id) and x.lifted_at is null)
            order by c.activated_at desc, c.id desc limit :batch
            """, nativeQuery = true)
    List<ChatConversationEntity> candidates(@Param("viewer") UUID viewer, @Param("before") Instant before,
            @Param("beforeId") UUID beforeId, @Param("batch") int batch);
    Optional<ChatConversationEntity> findByFirstProfileIdAndSecondProfileId(UUID first, UUID second);
    @Query("""
            select conversation from ChatConversationEntity conversation
            where (conversation.firstProfileId = :viewer or conversation.secondProfileId = :viewer)
            and conversation.state = 'ACTIVE'
            order by conversation.activatedAt desc, conversation.id desc
            """)
    List<ChatConversationEntity> forViewer(@Param("viewer") UUID viewer, Pageable page);
    @Query("""
            select conversation from ChatConversationEntity conversation
            where conversation.firstProfileId = :viewer or conversation.secondProfileId = :viewer
            """)
    List<ChatConversationEntity> relationships(@Param("viewer") UUID viewer);
}
