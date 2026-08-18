package com.mentalbridge.identity.registration;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.security.SecureTokenService;

@Service
public class VerifyEmailService {

	private final RegistrationPersistence persistence;
	private final SecureTokenService tokens;
	private final Clock clock;

	public VerifyEmailService(RegistrationPersistence persistence, SecureTokenService tokens, Clock clock) {
		this.persistence = persistence;
		this.tokens = tokens;
		this.clock = clock;
	}

	@Transactional
	public VerifiedAccount verify(String challenge, UUID correlationId) {
		var now = clock.instant();
		var verification = persistence.findVerificationByTokenHash(tokens.hash(challenge))
				.orElseThrow(InvalidVerificationChallengeException::new);
		if (verification.consumedAt() != null && verification.status() == AccountStatus.ACTIVE) {
			return new VerifiedAccount(verification.accountId(), verification.role());
		}
		if (verification.invalidatedAt() != null || verification.consumedAt() != null
				|| !verification.expiresAt().isAfter(now)
				|| verification.status() != AccountStatus.PENDING_EMAIL_VERIFICATION) {
			throw new InvalidVerificationChallengeException();
		}
		persistence.activate(verification.accountId(), verification.tokenId(), correlationId, now);
		return new VerifiedAccount(verification.accountId(), verification.role());
	}

	public record VerifiedAccount(UUID accountId, RoleCode role) {
	}

}
