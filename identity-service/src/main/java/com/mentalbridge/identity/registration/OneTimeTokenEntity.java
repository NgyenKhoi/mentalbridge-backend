package com.mentalbridge.identity.registration;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "one_time_token")
public class OneTimeTokenEntity {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@Column(nullable = false, length = 32)
	private String purpose;

	@Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	@Column(name = "invalidated_at")
	private Instant invalidatedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected OneTimeTokenEntity() {
	}

	public OneTimeTokenEntity(UUID accountId, String purpose, String tokenHash, Instant expiresAt, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.accountId = accountId;
		this.purpose = purpose;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.createdAt = createdAt;
	}

	public void consume(Instant consumedAt) {
		this.consumedAt = consumedAt;
	}

	public UUID id() {
		return id;
	}

	public UUID accountId() {
		return accountId;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public Instant consumedAt() {
		return consumedAt;
	}

	public Instant invalidatedAt() {
		return invalidatedAt;
	}

}
