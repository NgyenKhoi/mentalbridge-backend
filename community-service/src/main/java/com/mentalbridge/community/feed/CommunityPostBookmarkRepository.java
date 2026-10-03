package com.mentalbridge.community.feed;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CommunityPostBookmarkRepository extends JpaRepository<CommunityPostBookmarkEntity,
		CommunityPostBookmarkEntity.CommunityPostBookmarkId> {

	List<CommunityPostBookmarkEntity> findAllByProfileIdAndPostIdIn(UUID profileId, List<UUID> postIds);
}
