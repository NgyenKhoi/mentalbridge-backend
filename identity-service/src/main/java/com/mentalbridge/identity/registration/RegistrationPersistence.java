package com.mentalbridge.identity.registration;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.mentalbridge.identity.account.AccountEntity;
import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.RoleCode;

@Repository
public class RegistrationPersistence {

	private final AccountRepository accounts;
	private final OneTimeTokenRepository tokens;
	private final OutboxEventRepository outbox;

	public RegistrationPersistence(AccountRepository accounts, OneTimeTokenRepository tokens,
			OutboxEventRepository outbox) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.outbox = outbox;
	}

	public RegistrationRecord create(String normalizedEmail, String passwordHash, RoleCode role, String tokenHash,
			Instant tokenExpiresAt, UUID correlationId, Instant now) {
		var account = accounts.save(AccountEntity.pending(normalizedEmail, passwordHash, role, now));
		tokens.save(new OneTimeTokenEntity(account.id(), "VERIFY_EMAIL", tokenHash, tokenExpiresAt, now));
		outbox.save(new OutboxEventEntity("identity.account.registered", account.id(), account.version(), correlationId,
				Map.of("accountId", account.id().toString(), "actorType", role.name(), "status", account.status().name()),
				now));
		return new RegistrationRecord(account.id(), account.status(), account.createdAt());
	}

	public Optional<VerificationRecord> findVerificationByTokenHash(String tokenHash) {
		return tokens.findByTokenHashAndPurposeForUpdate(tokenHash, "VERIFY_EMAIL").map(token -> {
			var account = accounts.findByIdForUpdate(token.accountId())
					.orElseThrow(InvalidVerificationChallengeException::new);
			return new VerificationRecord(token.id(), account.id(), account.status(), account.role(), token.expiresAt(),
					token.consumedAt(), token.invalidatedAt());
		});
	}

	public void activate(UUID accountId, UUID tokenId, UUID correlationId, Instant verifiedAt) {
		var token = tokens.findById(tokenId).orElseThrow(InvalidVerificationChallengeException::new);
		if (token.consumedAt() != null || token.invalidatedAt() != null || !token.expiresAt().isAfter(verifiedAt)) {
			throw new InvalidVerificationChallengeException();
		}
		var account = accounts.findByIdForUpdate(accountId).filter(existing -> existing.status() == AccountStatus.PENDING_EMAIL_VERIFICATION)
				.orElseThrow(InvalidVerificationChallengeException::new);
		token.consume(verifiedAt);
		account.activate(verifiedAt);
		accounts.flush();
		outbox.save(new OutboxEventEntity("identity.account.email-verified", account.id(), account.version(), correlationId,
				Map.of("accountId", account.id().toString(), "status", account.status().name()), verifiedAt));
	}

	public record RegistrationRecord(UUID accountId, AccountStatus status, Instant createdAt) {
	}

	public record VerificationRecord(UUID tokenId, UUID accountId, AccountStatus status, RoleCode role,
			Instant expiresAt, Instant consumedAt, Instant invalidatedAt) {
	}

}
