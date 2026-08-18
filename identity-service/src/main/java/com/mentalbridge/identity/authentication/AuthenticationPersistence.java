package com.mentalbridge.identity.authentication;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.mentalbridge.identity.account.AccountEntity;
import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountRoleRepository;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.RoleCode;

@Repository
public class AuthenticationPersistence {

	private final AccountRepository accounts;
	private final AccountRoleRepository roles;
	private final RefreshSessionRepository sessions;

	public AuthenticationPersistence(AccountRepository accounts, AccountRoleRepository roles,
			RefreshSessionRepository sessions) {
		this.accounts = accounts;
		this.roles = roles;
		this.sessions = sessions;
	}

	public Optional<CredentialRecord> findCredentialForUpdate(String normalizedEmail) {
		return accounts.findByEmailForUpdate(normalizedEmail).map(this::credential);
	}

	public void recordFailedLogin(UUID accountId, Instant attemptedAt) {
		var account = accounts.findByIdForUpdate(accountId).orElseThrow();
		account.recordFailedLogin(attemptedAt);
	}

	public void recordSuccessfulLogin(UUID accountId, RefreshSession session, Instant loginAt) {
		var account = accounts.findByIdForUpdate(accountId).orElseThrow();
		account.recordSuccessfulLogin(loginAt);
		sessions.save(RefreshSessionEntity.from(session));
	}

	public Optional<RefreshRecord> findRefreshForUpdate(String tokenHash) {
		return sessions.findByTokenHashForUpdate(tokenHash).map(session -> {
			var account = accounts.findByIdForUpdate(session.accountId()).orElseThrow(InvalidSessionException::new);
			return new RefreshRecord(session.id(), session.familyId(), session.accountId(), account.status(),
					session.tokenHash(), session.expiresAt(), session.revokedAt(), session.revokeReason(), roles(account.id()));
		});
	}

	public void rotateRefresh(UUID currentSessionId, RefreshSession successor, Instant rotatedAt) {
		var current = sessions.findByIdForUpdate(currentSessionId).filter(session -> session.revokedAt() == null)
				.orElseThrow(InvalidSessionException::new);
		current.revoke("ROTATED", rotatedAt);
		sessions.save(RefreshSessionEntity.from(successor));
	}

	public void revokeFamily(UUID familyId, String reason, Instant revokedAt) {
		var active = sessions.findActiveFamilyForUpdate(familyId);
		active.forEach(session -> session.revoke(reason, revokedAt));
	}

	public void revokeAll(UUID accountId, String reason, Instant revokedAt) {
		var active = sessions.findActiveAccountSessionsForUpdate(accountId);
		active.forEach(session -> session.revoke(reason, revokedAt));
	}

	private CredentialRecord credential(AccountEntity account) {
		return new CredentialRecord(account.id(), account.passwordHash(), account.status(), account.failedLoginCount(),
				account.lockedUntil(), roles(account.id()));
	}

	private Set<RoleCode> roles(UUID accountId) {
		return roles.findRoleCodes(accountId).stream().map(RoleCode::valueOf).collect(Collectors.toUnmodifiableSet());
	}

	public record CredentialRecord(UUID accountId, String passwordHash, AccountStatus status, int failedLoginCount,
			Instant lockedUntil, Set<RoleCode> roles) {
	}

	public record RefreshSession(UUID id, UUID familyId, UUID accountId, String tokenHash, String deviceLabel,
			Instant expiresAt, UUID rotatedFromId, Instant createdAt) {
	}

	public record RefreshRecord(UUID id, UUID familyId, UUID accountId, AccountStatus accountStatus, String tokenHash,
			Instant expiresAt, Instant revokedAt, String revokeReason, Set<RoleCode> roles) {
	}

}
