package com.mentalbridge.identity.credential;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.mentalbridge.identity.account.AccountEntity;
import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.authentication.RefreshSessionRepository;
import com.mentalbridge.identity.registration.InvalidVerificationChallengeException;
import com.mentalbridge.identity.registration.OneTimeTokenEntity;
import com.mentalbridge.identity.registration.OneTimeTokenRepository;

@Repository
public class CredentialLifecyclePersistence {

	private final AccountRepository accounts;
	private final OneTimeTokenRepository tokens;
	private final RefreshSessionRepository sessions;

	public CredentialLifecyclePersistence(AccountRepository accounts, OneTimeTokenRepository tokens,
			RefreshSessionRepository sessions) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.sessions = sessions;
	}

	public Optional<DeliveryTarget> replaceChallenge(String normalizedEmail, String purpose, String tokenHash,
			Instant expiresAt, Instant now) {
		var account = accounts.findByEmailForUpdate(normalizedEmail).orElse(null);
		if (account == null || !eligible(account, purpose)) {
			return Optional.empty();
		}
		tokens.findActiveForUpdate(account.id(), purpose).forEach(token -> token.invalidate(now));
		tokens.flush();
		tokens.saveAndFlush(new OneTimeTokenEntity(account.id(), purpose, tokenHash, expiresAt, now));
		return Optional.of(new DeliveryTarget(account.id(), account.email()));
	}

	public void resetPassword(String tokenHash, String passwordHash, Instant now) {
		var accountId = tokens.findAccountIdByTokenHashAndPurpose(tokenHash, "RESET_PASSWORD")
				.orElseThrow(InvalidVerificationChallengeException::new);
		var account = accounts.findByIdForUpdate(accountId)
				.orElseThrow(InvalidVerificationChallengeException::new);
		var token = tokens.findByTokenHashAndPurposeForUpdate(tokenHash, "RESET_PASSWORD")
				.orElseThrow(InvalidVerificationChallengeException::new);
		if (!eligible(account, "RESET_PASSWORD") || token.consumedAt() != null || token.invalidatedAt() != null
				|| !token.expiresAt().isAfter(now)) {
			throw new InvalidVerificationChallengeException();
		}
		token.consume(now);
		account.replacePassword(passwordHash, now);
		revokeAll(account.id(), "PASSWORD_RESET", now);
		accounts.flush();
		tokens.flush();
	}

	public AccountEntity accountForPasswordChange(UUID accountId) {
		return accounts.findByIdForUpdate(accountId).filter(account -> account.status() == AccountStatus.ACTIVE)
				.orElseThrow(com.mentalbridge.identity.authentication.InvalidCredentialsException::new);
	}

	public void changePassword(AccountEntity account, String passwordHash, Instant now) {
		account.replacePassword(passwordHash, now);
		revokeAll(account.id(), "PASSWORD_CHANGE", now);
		accounts.flush();
	}

	private void revokeAll(UUID accountId, String reason, Instant now) {
		sessions.findActiveAccountSessionsForUpdate(accountId).forEach(session -> session.revoke(reason, now));
	}

	private boolean eligible(AccountEntity account, String purpose) {
		return switch (purpose) {
			case "VERIFY_EMAIL" -> account.status() == AccountStatus.PENDING_EMAIL_VERIFICATION
					&& account.emailVerifiedAt() == null;
			case "RESET_PASSWORD" -> account.status() == AccountStatus.ACTIVE
					&& account.emailVerifiedAt() != null;
			default -> false;
		};
	}

	public record DeliveryTarget(UUID accountId, String normalizedEmail) {
	}
}
