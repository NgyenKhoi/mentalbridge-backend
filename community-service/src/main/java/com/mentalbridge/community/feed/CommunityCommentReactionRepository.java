package com.mentalbridge.community.feed;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CommunityCommentReactionRepository extends JpaRepository<CommunityCommentReactionEntity, CommunityCommentReactionEntity.ReactionId> {
    long countByIdCommentId(UUID commentId);
}
