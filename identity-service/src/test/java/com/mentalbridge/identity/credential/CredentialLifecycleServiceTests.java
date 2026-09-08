package com.mentalbridge.identity.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.mentalbridge.identity.account.AccountEntity;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.authentication.InvalidCredentialsException;
import com.mentalbridge.identity.security.BCryptPasswordHasher;
import com.mentalbridge.identity.security.SecureTokenService;

class CredentialLifecycleServiceTests {

	private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
	private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID CORRELATION_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");

	private final CredentialLifecyclePersistence persistence = mock(CredentialLifecyclePersistence.class);
	private final CredentialRequestRateLimiter rateLimiter = mock(CredentialRequestRateLimiter.class);
	private final BCryptPasswordHasher passwordHasher = mock(BCryptPasswordHasher.class);
	private final SecureTokenService tokens = mock(SecureTokenService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private CredentialLifecycleService service;

	@BeforeEach
	void setUp() {
		service = new CredentialLifecycleService(persistence, rateLimiter, passwordHasher, tokens, events,
				Clock.fixed(NOW, ZoneOffset.UTC));
		when(tokens.generate()).thenReturn("opaque-challenge");
		when(tokens.hash("opaque-challenge")).thenReturn("challenge-hash");
	}

	@Test
	void verificationRequestNormalizesTheAddressAndPublishesOnlyTheDeliveryEvent() {
		when(persistence.replaceChallenge("member@example.com", "VERIFY_EMAIL", "challenge-hash",
				NOW.plusSeconds(24 * 60 * 60), NOW))
				.thenReturn(Optional.of(new CredentialLifecyclePersistence.DeliveryTarget(ACCOUNT_ID,
						"member@example.com")));

		service.requestEmailVerification(" Member@Example.COM ", CORRELATION_ID);

		verify(rateLimiter).claim("member@example.com", "VERIFY_EMAIL", NOW);
		var event = ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
		verify(events).publishEvent(event.capture());
		assertThat(event.getValue()).satisfies(requested -> {
			assertThat(requested.accountId()).isEqualTo(ACCOUNT_ID);
			assertThat(requested.normalizedEmail()).isEqualTo("member@example.com");
			assertThat(requested.challenge()).isEqualTo("opaque-challenge");
			assertThat(requested.purpose()).isEqualTo(CredentialDeliveryRequested.Purpose.VERIFY_EMAIL);
			assertThat(requested.correlationId()).isEqualTo(CORRELATION_ID);
			assertThat(requested.toString()).doesNotContain("opaque-challenge", "member@example.com");
		});
	}

	@Test
	void ineligibleRecoveryRequestStillClaimsThePrivacyLimitButPublishesNothing() {
		when(persistence.replaceChallenge("unknown@example.com", "RESET_PASSWORD", "challenge-hash",
				NOW.plusSeconds(15 * 60), NOW)).thenReturn(Optional.empty());

		service.requestPasswordRecovery("unknown@example.com", CORRELATION_ID);

		verify(rateLimiter).claim("unknown@example.com", "RESET_PASSWORD", NOW);
		verify(events, never()).publishEvent(any());
	}

	@Test
	void resetHashesTheNewPasswordBeforeDelegatingTheAtomicMutation() {
		when(tokens.hash("reset-challenge")).thenReturn("reset-hash");
		when(passwordHasher.hash("new-secure-password")).thenReturn("bcrypt-hash");

		service.resetPassword("reset-challenge", "new-secure-password");

		verify(persistence).resetPassword("reset-hash", "bcrypt-hash", NOW);
	}

	@Test
	void passwordChangeRequiresTheCurrentCredentialBeforeHashingTheReplacement() {
		var account = AccountEntity.pending("member@example.com", "stored-hash", RoleCode.USER, NOW);
		when(persistence.accountForPasswordChange(ACCOUNT_ID)).thenReturn(account);
		when(passwordHasher.matches("wrong-password", "stored-hash")).thenReturn(false);

		assertThatThrownBy(() -> service.changePassword(ACCOUNT_ID, "wrong-password", "new-secure-password"))
				.isInstanceOf(InvalidCredentialsException.class);

		verify(passwordHasher, never()).hash(anyString());
		verify(persistence, never()).changePassword(any(), anyString(), any());
	}

	@Test
	void passwordChangeHashesAndDelegatesAfterTheCurrentCredentialMatches() {
		var account = AccountEntity.pending("member@example.com", "stored-hash", RoleCode.USER, NOW);
		when(persistence.accountForPasswordChange(ACCOUNT_ID)).thenReturn(account);
		when(passwordHasher.matches("current-password", "stored-hash")).thenReturn(true);
		when(passwordHasher.hash("new-secure-password")).thenReturn("new-bcrypt-hash");

		service.changePassword(ACCOUNT_ID, "current-password", "new-secure-password");

		verify(persistence).changePassword(account, "new-bcrypt-hash", NOW);
	}
}
