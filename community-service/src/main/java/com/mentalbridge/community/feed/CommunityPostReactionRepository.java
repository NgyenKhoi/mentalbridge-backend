package com.mentalbridge.community.feed;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CommunityPostReactionRepository extends JpaRepository<CommunityPostReactionEntity,
		CommunityPostReactionEntity.CommunityPostReactionId> {

	List<CommunityPostReactionEntity> findAllByProfileIdAndPostIdIn(UUID profileId, List<UUID> postIds);
}
