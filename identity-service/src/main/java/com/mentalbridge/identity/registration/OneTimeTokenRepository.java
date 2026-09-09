package com.mentalbridge.identity.registration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface OneTimeTokenRepository extends JpaRepository<OneTimeTokenEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select token from OneTimeTokenEntity token where token.tokenHash = :tokenHash and token.purpose = :purpose")
	Optional<OneTimeTokenEntity> findByTokenHashAndPurposeForUpdate(@Param("tokenHash") String tokenHash,
			@Param("purpose") String purpose);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select token from OneTimeTokenEntity token where token.accountId = :accountId and token.purpose = :purpose and token.consumedAt is null and token.invalidatedAt is null")
	List<OneTimeTokenEntity> findActiveForUpdate(@Param("accountId") UUID accountId,
			@Param("purpose") String purpose);

}
