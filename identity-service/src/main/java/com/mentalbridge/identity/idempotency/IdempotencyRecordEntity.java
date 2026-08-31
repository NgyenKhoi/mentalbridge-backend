package com.mentalbridge.identity.idempotency;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity {

	@Id
	private UUID id;

	@Column(nullable = false, length = 64)
	private String operation;

	@Column(name = "idempotency_key", nullable = false, length = 128)
	private String idempotencyKey;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "request_hash", nullable = false, length = 64)
	private String requestHash;

	@Column(name = "account_id")
	private UUID accountId;

	@Column(name = "response_status")
	private Short responseStatus;

	@Column(name = "response_ciphertext")
	private byte[] responseCiphertext;

	@Column(name = "encryption_key_version", length = 64)
	private String encryptionKeyVersion;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected IdempotencyRecordEntity() {
	}

	public void complete(UUID accountId, int responseStatus, byte[] responseCiphertext, String keyVersion,
			Instant completedAt) {
		if (this.completedAt == null) {
			this.accountId = accountId;
			this.responseStatus = (short) responseStatus;
			this.responseCiphertext = responseCiphertext.clone();
			this.encryptionKeyVersion = keyVersion;
			this.completedAt = completedAt;
		}
	}

	public UUID id() {
		return id;
	}

	public String requestHash() {
		return requestHash;
	}

	public UUID accountId() {
		return accountId;
	}

	public Integer responseStatus() {
		return responseStatus == null ? null : responseStatus.intValue();
	}

	public byte[] responseCiphertext() {
		return responseCiphertext == null ? null : responseCiphertext.clone();
	}

	public String encryptionKeyVersion() {
		return encryptionKeyVersion;
	}

	public Instant completedAt() {
		return completedAt;
	}

}
