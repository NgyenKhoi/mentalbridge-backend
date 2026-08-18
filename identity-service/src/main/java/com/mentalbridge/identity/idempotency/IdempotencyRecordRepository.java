package com.mentalbridge.identity.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, UUID> {

	@Modifying
	@Query(value = """
			insert into idempotency_record (
			    id, operation, idempotency_key, request_hash, expires_at, created_at
			) values (:id, :operation, :key, :requestHash, :expiresAt, :createdAt)
			on conflict (operation, idempotency_key) do nothing
			""", nativeQuery = true)
	int claim(@Param("id") UUID id, @Param("operation") String operation, @Param("key") String key,
			@Param("requestHash") String requestHash, @Param("expiresAt") Instant expiresAt,
			@Param("createdAt") Instant createdAt);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select record from IdempotencyRecordEntity record where record.operation = :operation and record.idempotencyKey = :key")
	Optional<IdempotencyRecordEntity> findForUpdate(@Param("operation") String operation, @Param("key") String key);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select record from IdempotencyRecordEntity record where record.id = :id")
	Optional<IdempotencyRecordEntity> findByIdForUpdate(@Param("id") UUID id);

}
