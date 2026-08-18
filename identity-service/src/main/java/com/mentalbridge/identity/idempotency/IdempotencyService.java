package com.mentalbridge.identity.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {

	private final IdempotencyRecordRepository records;

	public IdempotencyService(IdempotencyRecordRepository records) {
		this.records = records;
	}

	public boolean claim(UUID id, String operation, String key, String requestHash, Instant expiresAt,
			Instant createdAt) {
		return records.claim(id, operation, key, requestHash, expiresAt, createdAt) == 1;
	}

	public Optional<IdempotencyRecord> findForUpdate(String operation, String key) {
		return records.findForUpdate(operation, key).map(record -> new IdempotencyRecord(record.id(),
				record.requestHash(), record.accountId(), record.responseStatus(), record.responseCiphertext(),
				record.encryptionKeyVersion(), record.completedAt()));
	}

	public void complete(UUID id, UUID accountId, int responseStatus, byte[] responseCiphertext, String keyVersion,
			Instant completedAt) {
		var record = records.findByIdForUpdate(id).orElseThrow();
		record.complete(accountId, responseStatus, responseCiphertext, keyVersion, completedAt);
	}

	public record IdempotencyRecord(UUID id, String requestHash, UUID accountId, Integer responseStatus,
			byte[] responseCiphertext, String encryptionKeyVersion, Instant completedAt) {
	}

}
