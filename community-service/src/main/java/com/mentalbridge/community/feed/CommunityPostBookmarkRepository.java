package com.mentalbridge.community.feed;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityPostBookmarkRepository extends JpaRepository<CommunityPostBookmarkEntity,
		CommunityPostBookmarkEntity.CommunityPostBookmarkId> {

	List<CommunityPostBookmarkEntity> findAllByProfileIdAndPostIdIn(UUID profileId, List<UUID> postIds);

	@Query(value = """
			SELECT post.id AS "postId", bookmark.created_at AS "savedAt"
			FROM community_post_bookmark bookmark
			JOIN community_post post ON post.id = bookmark.post_id
			WHERE bookmark.profile_id = :viewerProfileId
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
			  AND (CAST(:cursorSavedAt AS timestamptz) IS NULL
			       OR bookmark.created_at < :cursorSavedAt
			       OR (bookmark.created_at = :cursorSavedAt AND post.id < :cursorPostId))
			ORDER BY bookmark.created_at DESC, post.id DESC
			""", nativeQuery = true)
	List<SavedPostReference> findVisibleSavedPosts(@Param("viewerProfileId") UUID viewerProfileId,
			@Param("cursorSavedAt") Instant cursorSavedAt, @Param("cursorPostId") UUID cursorPostId,
			Pageable pageable);

	interface SavedPostReference {

		UUID getPostId();

		Instant getSavedAt();
	}
}
