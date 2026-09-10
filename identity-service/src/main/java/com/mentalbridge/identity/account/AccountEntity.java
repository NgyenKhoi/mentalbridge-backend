package com.mentalbridge.identity.account;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "account")
public class AccountEntity {

	@Id
	private UUID id;

	@Column(nullable = false, columnDefinition = "citext")
	private String email;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "role_code", nullable = false, updatable = false, length = 32)
	private RoleCode role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private AccountStatus status;

	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	@Column(name = "failed_login_count", nullable = false)
	private int failedLoginCount;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(nullable = false)
	private long version;

	protected AccountEntity() {
	}

	public static AccountEntity pending(String normalizedEmail, String passwordHash, RoleCode role, Instant now) {
		var account = new AccountEntity();
		account.id = UUID.randomUUID();
		account.email = normalizedEmail;
		account.passwordHash = passwordHash;
		account.role = role;
		account.status = AccountStatus.PENDING_EMAIL_VERIFICATION;
		account.createdAt = now;
		account.updatedAt = now;
		return account;
	}

	public void activate(Instant verifiedAt) {
		status = AccountStatus.ACTIVE;
		emailVerifiedAt = verifiedAt;
		updatedAt = verifiedAt;
	}

	public void recordFailedLogin(Instant attemptedAt) {
		failedLoginCount = lockedUntil != null && !lockedUntil.isAfter(attemptedAt) ? 1 : failedLoginCount + 1;
		if (failedLoginCount >= 5) {
			lockedUntil = attemptedAt.plus(Duration.ofMinutes(15));
		}
		updatedAt = attemptedAt;
	}

	public void recordSuccessfulLogin(Instant loginAt) {
		failedLoginCount = 0;
		lockedUntil = null;
		lastLoginAt = loginAt;
		updatedAt = loginAt;
	}

	public void replacePassword(String passwordHash, Instant changedAt) {
		this.passwordHash = passwordHash;
		failedLoginCount = 0;
		lockedUntil = null;
		updatedAt = changedAt;
	}

	public UUID id() {
		return id;
	}

	public String email() {
		return email;
	}

	public String passwordHash() {
		return passwordHash;
	}

	public RoleCode role() {
		return role;
	}

	public AccountStatus status() {
		return status;
	}

	public Instant emailVerifiedAt() {
		return emailVerifiedAt;
	}

	public int failedLoginCount() {
		return failedLoginCount;
	}

	public Instant lockedUntil() {
		return lockedUntil;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	public long version() {
		return version;
	}

}
