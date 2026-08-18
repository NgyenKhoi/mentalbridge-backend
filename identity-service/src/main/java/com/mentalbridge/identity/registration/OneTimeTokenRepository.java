package com.mentalbridge.identity.registration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface OneTimeTokenRepository extends JpaRepository<OneTimeTokenEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select token from OneTimeTokenEntity token where token.tokenHash = :tokenHash and token.purpose = 'VERIFY_EMAIL'")
	Optional<OneTimeTokenEntity> findVerificationForUpdate(@Param("tokenHash") String tokenHash);

}
