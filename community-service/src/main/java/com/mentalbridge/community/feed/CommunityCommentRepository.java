package com.mentalbridge.community.feed;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityCommentRepository extends JpaRepository<CommunityCommentEntity, UUID> {

	@Query(value = """
			SELECT comment.*
			FROM community_comment comment
			LEFT JOIN community_comment parent ON parent.id = comment.parent_comment_id
			WHERE comment.post_id = :postId
			  AND comment.state IN ('ACTIVE', 'OWNER_DELETED')
			  AND (comment.parent_comment_id IS NULL OR parent.state IN ('ACTIVE', 'OWNER_DELETED'))
			  AND (:viewerProfileId IS NULL OR NOT EXISTS (
			      SELECT 1 FROM community_block block
			      WHERE (block.blocker_profile_id = :viewerProfileId AND block.blocked_profile_id = comment.author_profile_id)
			         OR (block.blocker_profile_id = comment.author_profile_id AND block.blocked_profile_id = :viewerProfileId)
			  ))
			  AND (:viewerProfileId IS NULL OR parent.id IS NULL OR NOT EXISTS (
			      SELECT 1 FROM community_block block
			      WHERE (block.blocker_profile_id = :viewerProfileId AND block.blocked_profile_id = parent.author_profile_id)
			         OR (block.blocker_profile_id = parent.author_profile_id AND block.blocked_profile_id = :viewerProfileId)
			  ))
			  AND (CAST(:cursorCreatedAt AS timestamptz) IS NULL
			       OR comment.created_at > :cursorCreatedAt
			       OR (comment.created_at = :cursorCreatedAt AND comment.id > :cursorCommentId))
			ORDER BY comment.created_at, comment.id
			""", nativeQuery = true)
	List<CommunityCommentEntity> findVisiblePage(@Param("postId") UUID postId,
			@Param("viewerProfileId") UUID viewerProfileId,
			@Param("cursorCreatedAt") Instant cursorCreatedAt,
			@Param("cursorCommentId") UUID cursorCommentId,
			Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select comment from CommunityCommentEntity comment
			where comment.author.id = :authorId and comment.idempotencyKey = :idempotencyKey
			""")
	Optional<CommunityCommentEntity> findByAuthorAndIdempotencyKeyForUpdate(@Param("authorId") UUID authorId,
			@Param("idempotencyKey") String idempotencyKey);

	@Query(value = """
			SELECT comment.*
			FROM community_comment comment
			WHERE comment.id = :commentId
			  AND comment.post_id = :postId
			  AND comment.parent_comment_id IS NULL
			  AND comment.state = 'ACTIVE'
			  AND NOT EXISTS (
			      SELECT 1 FROM community_block block
			      WHERE (block.blocker_profile_id = :viewerProfileId AND block.blocked_profile_id = comment.author_profile_id)
			         OR (block.blocker_profile_id = comment.author_profile_id AND block.blocked_profile_id = :viewerProfileId)
			  )
			FOR UPDATE
			""", nativeQuery = true)
	Optional<CommunityCommentEntity> findVisibleRootForUpdate(@Param("commentId") UUID commentId,
			@Param("postId") UUID postId, @Param("viewerProfileId") UUID viewerProfileId);

	@Query(value = """
			SELECT comment.*
			FROM community_comment comment
			JOIN community_profile author ON author.id = comment.author_profile_id
			JOIN community_post post ON post.id = comment.post_id
			WHERE comment.id = :commentId
			  AND author.account_subject = :subject
			  AND comment.state = 'ACTIVE'
			  AND post.state = 'ACTIVE'
			  AND NOT EXISTS (
			      SELECT 1 FROM community_block block
			      WHERE (block.blocker_profile_id = comment.author_profile_id AND block.blocked_profile_id = post.author_profile_id)
			         OR (block.blocker_profile_id = post.author_profile_id AND block.blocked_profile_id = comment.author_profile_id)
			  )
			FOR UPDATE OF post, comment
			""", nativeQuery = true)
	Optional<CommunityCommentEntity> findOwnedActiveVisibleForUpdate(@Param("commentId") UUID commentId,
			@Param("subject") UUID subject);
}
