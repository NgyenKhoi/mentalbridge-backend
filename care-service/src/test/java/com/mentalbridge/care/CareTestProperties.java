package com.mentalbridge.care;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mentalbridge.care.configuration.IdentityAccountStatusVerifier;

public abstract class CareTestProperties {

	private static final KeyPair JWT_KEY_PAIR = keyPair();

	@MockitoBean
	protected IdentityAccountStatusVerifier accountStatusVerifier;

	@BeforeEach
	protected void defaultActiveAccountStatus() {
		when(accountStatusVerifier.isActive(any(), any())).thenReturn(true);
	}

	@DynamicPropertySource
	static void careProperties(DynamicPropertyRegistry properties) {
		properties.add("mentalbridge.care.jwt.issuer", () -> "https://identity.test.mentalbridge");
		properties.add("mentalbridge.care.jwt.audience", () -> "mentalbridge-test-api");
		properties.add("mentalbridge.care.jwt.public-key",
				() -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
		properties.add("mentalbridge.care.identity-account-status.connect-timeout", () -> "PT0.25S");
		properties.add("mentalbridge.care.identity-account-status.read-timeout", () -> "PT0.75S");
		properties.add("mentalbridge.care.assessment.anonymous-session-ttl", () -> "PT30M");
		properties.add("mentalbridge.care.assessment.anonymous-session-maximum-lifetime", () -> "PT2H");
		properties.add("mentalbridge.care.assessment.phq9-safety-policy-version",
				() -> "MB-SAFETY-PHQ9-001-test-v1");
		properties.add("mentalbridge.care.assessment.idempotency-hmac-key",
				() -> "test-only-idempotency-hmac-key-at-least-32-characters");
		properties.add("mentalbridge.care.voice-consent.idempotency-hmac-key",
				() -> "test-only-idempotency-hmac-key-at-least-32-characters");
	}

	private static KeyPair keyPair() {
		try {
			var generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to create test JWT key", exception);
		}
	}
}
