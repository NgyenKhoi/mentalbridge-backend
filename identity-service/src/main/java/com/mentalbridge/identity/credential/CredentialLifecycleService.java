package com.mentalbridge.identity.credential;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.authentication.InvalidCredentialsException;
import com.mentalbridge.identity.security.BCryptPasswordHasher;
import com.mentalbridge.identity.security.SecureTokenService;

@Service
public class CredentialLifecycleService {

	private static final Duration VERIFICATION_LIFETIME = Duration.ofHours(24);
	private static final Duration RECOVERY_LIFETIME = Duration.ofMinutes(15);

	private final CredentialLifecyclePersistence persistence;
	private final BCryptPasswordHasher passwordHasher;
	private final SecureTokenService tokens;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public CredentialLifecycleService(CredentialLifecyclePersistence persistence, BCryptPasswordHasher passwordHasher,
			SecureTokenService tokens, ApplicationEventPublisher events, Clock clock) {
		this.persistence = persistence;
		this.passwordHasher = passwordHasher;
		this.tokens = tokens;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public void requestEmailVerification(String email, UUID correlationId) {
		request(email, CredentialDeliveryRequested.Purpose.VERIFY_EMAIL, VERIFICATION_LIFETIME, correlationId);
	}

	@Transactional
	public void requestPasswordRecovery(String email, UUID correlationId) {
		request(email, CredentialDeliveryRequested.Purpose.RESET_PASSWORD, RECOVERY_LIFETIME, correlationId);
	}

	@Transactional
	public void resetPassword(String challenge, String newPassword) {
		var now = clock.instant();
		persistence.resetPassword(tokens.hash(challenge), passwordHasher.hash(newPassword), now);
	}

	@Transactional
	public void changePassword(UUID accountId, String currentPassword, String newPassword) {
		var now = clock.instant();
		var account = persistence.accountForPasswordChange(accountId);
		if (!passwordHasher.matches(currentPassword, account.passwordHash())) {
			throw new InvalidCredentialsException();
		}
		persistence.changePassword(account, passwordHasher.hash(newPassword), now);
	}

	private void request(String email, CredentialDeliveryRequested.Purpose purpose, Duration lifetime,
			UUID correlationId) {
		var now = clock.instant();
		var normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
		var challenge = tokens.generate();
		persistence.replaceChallenge(normalizedEmail, purpose.name(), tokens.hash(challenge), now.plus(lifetime), now)
				.ifPresent(target -> events.publishEvent(new CredentialDeliveryRequested(target.accountId(),
						target.normalizedEmail(), challenge, purpose, correlationId)));
	}
}
