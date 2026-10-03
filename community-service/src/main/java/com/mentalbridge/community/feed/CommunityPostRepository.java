package com.mentalbridge.community.feed;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface CommunityPostRepository extends JpaRepository<CommunityPostEntity, UUID> {

	@Query(value = """
			SELECT post.*
			FROM community_post post
			WHERE post.state = 'ACTIVE'
			  AND (:topicCode IS NULL OR EXISTS (
			      SELECT 1 FROM community_post_topic topic
			      WHERE topic.post_id = post.id AND topic.topic_code = :topicCode
			  ))
			  AND (:viewerProfileId IS NULL OR NOT EXISTS (
			      SELECT 1 FROM community_block block
			      WHERE (block.blocker_profile_id = :viewerProfileId AND block.blocked_profile_id = post.author_profile_id)
			         OR (block.blocker_profile_id = post.author_profile_id AND block.blocked_profile_id = :viewerProfileId)
			  ))
			  AND (:viewerProfileId IS NULL OR NOT EXISTS (
			      SELECT 1 FROM community_content_hide hidden
			      WHERE hidden.hider_profile_id = :viewerProfileId
			        AND hidden.target_type = 'POST' AND hidden.target_id = post.id
			  ))
			  AND (CAST(:cursorPublishedAt AS timestamptz) IS NULL
			       OR post.published_at < :cursorPublishedAt
			       OR (post.published_at = :cursorPublishedAt AND post.id < :cursorPostId))
			ORDER BY post.published_at DESC, post.id DESC
			""", nativeQuery = true)
	List<CommunityPostEntity> findFeed(@Param("topicCode") String topicCode,
			@Param("viewerProfileId") UUID viewerProfileId,
			@Param("cursorPublishedAt") Instant cursorPublishedAt,
			@Param("cursorPostId") UUID cursorPostId,
			Pageable pageable);

	@Query(value = """
			SELECT post.*
			FROM community_post post
			WHERE post.id = :postId
			  AND post.state = 'ACTIVE'
			  AND (:viewerProfileId IS NULL OR NOT EXISTS (
			      SELECT 1 FROM community_block block
			      WHERE (block.blocker_profile_id = :viewerProfileId AND block.blocked_profile_id = post.author_profile_id)
			         OR (block.blocker_profile_id = post.author_profile_id AND block.blocked_profile_id = :viewerProfileId)
			  ))
			  AND (:viewerProfileId IS NULL OR NOT EXISTS (
			      SELECT 1 FROM community_content_hide hidden
			      WHERE hidden.hider_profile_id = :viewerProfileId
			        AND hidden.target_type = 'POST' AND hidden.target_id = post.id
			  ))
			""", nativeQuery = true)
	Optional<CommunityPostEntity> findVisibleById(@Param("postId") UUID postId,
			@Param("viewerProfileId") UUID viewerProfileId);

	@Query(value = """
			SELECT post.*
			FROM community_post post
			WHERE post.id = :postId
			  AND post.state = 'ACTIVE'
			  AND NOT EXISTS (
			      SELECT 1 FROM community_block block
			      WHERE (block.blocker_profile_id = :viewerProfileId AND block.blocked_profile_id = post.author_profile_id)
			         OR (block.blocker_profile_id = post.author_profile_id AND block.blocked_profile_id = :viewerProfileId)
			  )
			  AND NOT EXISTS (
			      SELECT 1 FROM community_content_hide hidden
			      WHERE hidden.hider_profile_id = :viewerProfileId
			        AND hidden.target_type = 'POST' AND hidden.target_id = post.id
			  )
			FOR UPDATE
			""", nativeQuery = true)
	Optional<CommunityPostEntity> findVisibleByIdForUpdate(@Param("postId") UUID postId,
			@Param("viewerProfileId") UUID viewerProfileId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select post from CommunityPostEntity post
			where post.author.id = :ownerId and post.idempotencyKey = :idempotencyKey
			""")
	Optional<CommunityPostEntity> findByOwnerAndIdempotencyKeyForUpdate(@Param("ownerId") UUID ownerId,
			@Param("idempotencyKey") String idempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select post from CommunityPostEntity post
			where post.id = :postId and post.author.accountSubject = :subject and post.state = :state
			""")
	Optional<CommunityPostEntity> findOwnedActiveByIdForUpdate(@Param("postId") UUID postId,
			@Param("subject") UUID subject, @Param("state") CommunityPostEntity.State state);
}
