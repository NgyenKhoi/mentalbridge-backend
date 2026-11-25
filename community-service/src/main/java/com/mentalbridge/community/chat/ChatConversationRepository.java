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
    @Query("""
            select c from ChatConversationEntity c where c.firstProfileId = :viewer or c.secondProfileId = :viewer
            or exists (select m.id from ChatMembershipEntity m where m.conversationId = c.id and m.profileId = :viewer and m.departedAt is null)
            """)
    List<ChatConversationEntity> visibleTo(@Param("viewer") UUID viewer);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChatConversationEntity c where c.id = :id")
    Optional<ChatConversationEntity> lock(@Param("id") UUID id);
    @Query(value = """
            select c.id as id, case when a.id = :viewer then b.id else a.id end as "peerId",
            case when a.id = :viewer then b.account_subject else a.account_subject end as "peerSubject",
            activity.sort_at as "activityAt" from community_chat_conversation c
            join community_profile a on a.id = c.first_profile_id join community_profile b on b.id = c.second_profile_id
            cross join lateral (select coalesce(max(m.created_at), c.activated_at) as sort_at
                from community_chat_message m where m.conversation_id = c.id and m.content_ciphertext is not null and m.expires_at > :now) activity
            where (a.id = :viewer or b.id = :viewer) and c.state = 'ACTIVE' and a.status = 'ACTIVE' and b.status = 'ACTIVE'
            and (activity.sort_at < :before or (activity.sort_at = :before and c.id < :beforeId))
            and not exists (select 1 from community_block x where
              (x.blocker_profile_id = a.id and x.blocked_profile_id = b.id) or (x.blocker_profile_id = b.id and x.blocked_profile_id = a.id))
            and not exists (select 1 from community_access_restriction x where x.profile_id in (a.id, b.id) and x.lifted_at is null)
            order by activity.sort_at desc, c.id desc limit :batch
            """, nativeQuery = true)
    List<ConversationCandidate> candidates(@Param("viewer") UUID viewer, @Param("before") Instant before,
            @Param("beforeId") UUID beforeId, @Param("batch") int batch, @Param("now") Instant now);
    interface ConversationCandidate { UUID getId(); UUID getPeerId(); UUID getPeerSubject(); Instant getActivityAt(); }
    Optional<ChatConversationEntity> findByFirstProfileIdAndSecondProfileId(UUID first, UUID second);
    @Query("""
            select conversation from ChatConversationEntity conversation
            where (conversation.firstProfileId = :viewer or conversation.secondProfileId = :viewer)
            and conversation.state = 'ACTIVE'
            order by conversation.activatedAt desc, conversation.id desc
            """)
    List<ChatConversationEntity> forViewer(@Param("viewer") UUID viewer, Pageable page);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select conversation from ChatConversationEntity conversation
            where conversation.firstProfileId = :viewer or conversation.secondProfileId = :viewer
            or exists (select m.id from ChatMembershipEntity m where m.conversationId = conversation.id and m.profileId = :viewer and m.departedAt is null)
            order by conversation.id
            """)
    List<ChatConversationEntity> relationships(@Param("viewer") UUID viewer);
}
