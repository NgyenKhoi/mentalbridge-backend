package com.mentalbridge.identity.authentication;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.security.SecureTokenService;

@Service
public class LogoutService {

	private final AuthenticationPersistence persistence;
	private final SecureTokenService tokens;
	private final Clock clock;

	public LogoutService(AuthenticationPersistence persistence, SecureTokenService tokens, Clock clock) {
		this.persistence = persistence;
		this.tokens = tokens;
		this.clock = clock;
	}

	@Transactional
	public void logout(UUID accountId, String refreshToken) {
		persistence.findRefreshForUpdate(tokens.hash(refreshToken)).filter(session -> session.accountId().equals(accountId))
				.ifPresent(session -> persistence.revokeFamily(session.familyId(), "LOGOUT", clock.instant()));
	}

	@Transactional
	public void logoutAll(UUID accountId) {
		persistence.revokeAll(accountId, "LOGOUT_ALL", clock.instant());
	}

}
