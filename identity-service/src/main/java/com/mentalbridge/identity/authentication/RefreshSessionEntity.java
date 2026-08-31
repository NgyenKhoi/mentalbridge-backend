package com.mentalbridge.identity.authentication;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "refresh_session")
public class RefreshSessionEntity {

	@Id
	private UUID id;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "device_label", length = 120)
	private String deviceLabel;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "ip_hash", length = 64)
	private String ipHash;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "user_agent_hash", length = 64)
	private String userAgentHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "rotated_from_id")
	private UUID rotatedFromId;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoke_reason", length = 64)
	private String revokeReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected RefreshSessionEntity() {
	}

	public static RefreshSessionEntity from(AuthenticationPersistence.RefreshSession session) {
		var entity = new RefreshSessionEntity();
		entity.id = session.id();
		entity.familyId = session.familyId();
		entity.accountId = session.accountId();
		entity.tokenHash = session.tokenHash();
		entity.deviceLabel = session.deviceLabel();
		entity.expiresAt = session.expiresAt();
		entity.rotatedFromId = session.rotatedFromId();
		entity.createdAt = session.createdAt();
		return entity;
	}

	public void revoke(String reason, Instant revokedAt) {
		if (this.revokedAt == null) {
			this.revokedAt = revokedAt;
			this.revokeReason = reason;
		}
	}

	public UUID id() {
		return id;
	}

	public UUID familyId() {
		return familyId;
	}

	public UUID accountId() {
		return accountId;
	}

	public String tokenHash() {
		return tokenHash;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public Instant revokedAt() {
		return revokedAt;
	}

	public String revokeReason() {
		return revokeReason;
	}

}
