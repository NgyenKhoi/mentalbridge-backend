package com.mentalbridge.community.feed;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface CommunityProfileRepository extends JpaRepository<CommunityProfileEntity, UUID> {

	@Query("select profile.id from CommunityProfileEntity profile where profile.accountSubject = :subject")
	Optional<UUID> findIdByAccountSubject(@Param("subject") UUID subject);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select profile from CommunityProfileEntity profile where profile.accountSubject = :subject")
	Optional<CommunityProfileEntity> findByAccountSubjectForUpdate(@Param("subject") UUID subject);

	@Modifying
	@Query(value = """
			insert into community_profile
			(id, account_subject, display_name, status, created_at, updated_at, version)
			values (:id, :subject, :displayName, 'ACTIVE', :now, :now, 0)
			on conflict (account_subject) do nothing
			""", nativeQuery = true)
	int createIfAbsent(@Param("id") UUID id, @Param("subject") UUID subject,
			@Param("displayName") String displayName, @Param("now") java.time.Instant now);
}
