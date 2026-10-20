package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface ChatInvitationRepository extends JpaRepository<ChatInvitationEntity, UUID> {
    @Query("select i from ChatInvitationEntity i where i.state = 'PENDING' and (i.inviterProfileId = :profile or i.inviteeProfileId = :profile)")
    List<ChatInvitationEntity> pendingFor(@Param("profile") UUID profile);
    List<ChatInvitationEntity> findByConversationIdAndState(UUID conversation, String state);
    boolean existsByConversationIdAndInviteeProfileIdAndState(UUID conversation, UUID invitee, String state);
    long countByInviterProfileIdAndCreatedAtAfter(UUID inviter, Instant after);
    @Query(value = """
            select * from community_chat_invitation where invitee_profile_id = :viewer and state = 'PENDING'
            and expires_at > :now and (created_at < :before or (created_at = :before and id < :beforeId))
            order by created_at desc, id desc limit 50
            """, nativeQuery = true)
    List<ChatInvitationEntity> incoming(@Param("viewer") UUID viewer, @Param("now") Instant now,
            @Param("before") Instant before, @Param("beforeId") UUID beforeId);
}
