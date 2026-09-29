package com.mentalbridge.community.feed;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityProfileRepository extends JpaRepository<CommunityProfileEntity, UUID> {

	@Query("select profile.id from CommunityProfileEntity profile where profile.accountSubject = :subject")
	Optional<UUID> findIdByAccountSubject(@Param("subject") UUID subject);
}
