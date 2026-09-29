package com.mentalbridge.community.feed;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityMediaRepository extends JpaRepository<CommunityMediaEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select media from CommunityMediaEntity media where media.id in :ids")
	List<CommunityMediaEntity> findAllByIdForUpdate(@Param("ids") List<UUID> ids);
}
