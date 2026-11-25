package com.mentalbridge.community.feed;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityTopicRepository extends JpaRepository<CommunityTopicEntity, CommunityTopic> {

	List<CommunityTopicEntity> findByActiveTrueOrderByDisplayOrderAsc();

	@Query("select topic.code from CommunityTopicEntity topic where topic.active = true and topic.code in :codes")
	List<CommunityTopic> findActiveCodes(@Param("codes") Collection<CommunityTopic> codes);
}
