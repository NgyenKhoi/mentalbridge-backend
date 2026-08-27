package com.mentalbridge.identity.authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.security.BCryptPasswordHasher;
import com.mentalbridge.identity.security.SecureTokenService;

@Service
public class AuthenticateAccountService {

	private static final Duration REFRESH_LIFETIME = Duration.ofDays(30);

	private final AuthenticationPersistence persistence;
	private final BCryptPasswordHasher passwordHasher;
	private final SecureTokenService tokens;
	private final JwtTokenService tokenService;
	private final Clock clock;

	public AuthenticateAccountService(AuthenticationPersistence persistence, BCryptPasswordHasher passwordHasher,
			SecureTokenService tokens, JwtTokenService tokenService, Clock clock) {
		this.persistence = persistence;
		this.passwordHasher = passwordHasher;
		this.tokens = tokens;
		this.tokenService = tokenService;
		this.clock = clock;
	}

	@Transactional(noRollbackFor = InvalidCredentialsException.class)
	public TokenPair authenticate(String email, String password, String deviceLabel) {
		var now = clock.instant();
		var credential = persistence.findCredentialForUpdate(email.strip().toLowerCase(Locale.ROOT))
				.orElseThrow(InvalidCredentialsException::new);
		if (credential.status() != AccountStatus.ACTIVE
				|| credential.lockedUntil() != null && credential.lockedUntil().isAfter(now)) {
			throw new InvalidCredentialsException();
		}
		if (!passwordHasher.matches(password, credential.passwordHash())) {
			persistence.recordFailedLogin(credential.accountId(), now);
			throw new InvalidCredentialsException();
		}

		var refreshToken = tokens.generate();
		var session = new AuthenticationPersistence.RefreshSession(UUID.randomUUID(), UUID.randomUUID(),
				credential.accountId(), tokens.hash(refreshToken), deviceLabel, now.plus(REFRESH_LIFETIME), null, now);
		persistence.recordSuccessfulLogin(credential.accountId(), session, now);
		var accessToken = tokenService.issue(credential.accountId(), credential.role(), now);
		return new TokenPair(accessToken.value(), accessToken.expiresInSeconds(), refreshToken, session.expiresAt());
	}

	public record TokenPair(String accessToken, long expiresIn, String refreshToken, Instant refreshExpiresAt) {

		@Override
		public String toString() {
			return "TokenPair[accessToken=[REDACTED], expiresIn=" + expiresIn
					+ ", refreshToken=[REDACTED], refreshExpiresAt=" + refreshExpiresAt + "]";
		}
	}

}
