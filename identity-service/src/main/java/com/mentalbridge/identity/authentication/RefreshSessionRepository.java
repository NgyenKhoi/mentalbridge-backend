package com.mentalbridge.identity.authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RefreshSessionRepository extends JpaRepository<RefreshSessionEntity, UUID> {

	@Query("select session.accountId from RefreshSessionEntity session where session.tokenHash = :tokenHash")
	Optional<UUID> findAccountIdByTokenHash(@Param("tokenHash") String tokenHash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select session from RefreshSessionEntity session where session.tokenHash = :tokenHash")
	Optional<RefreshSessionEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select session from RefreshSessionEntity session where session.id = :id")
	Optional<RefreshSessionEntity> findByIdForUpdate(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select session from RefreshSessionEntity session where session.familyId = :familyId and session.revokedAt is null")
	List<RefreshSessionEntity> findActiveFamilyForUpdate(@Param("familyId") UUID familyId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select session from RefreshSessionEntity session where session.accountId = :accountId and session.revokedAt is null")
	List<RefreshSessionEntity> findActiveAccountSessionsForUpdate(@Param("accountId") UUID accountId);

}
