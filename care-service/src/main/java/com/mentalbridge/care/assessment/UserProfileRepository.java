package com.mentalbridge.care.assessment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface UserProfileRepository extends JpaRepository<UserProfileEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select profile from UserProfileEntity profile where profile.accountId = :accountId")
	Optional<UserProfileEntity> findByIdForUpdate(@Param("accountId") UUID accountId);
}
