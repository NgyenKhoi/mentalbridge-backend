package com.mentalbridge.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mentalbridge.identity.account.AccountStatusAuthorizationService;

public abstract class IdentityTestProperties {

	private static final KeyPair JWT_KEY_PAIR = keyPair();

	@MockitoBean
	protected AccountStatusAuthorizationService accountStatusAuthorization;

	@BeforeEach
	protected void defaultActiveAccountStatus() {
		when(accountStatusAuthorization.isActive(any())).thenReturn(true);
	}

	@DynamicPropertySource
	static void identityProperties(DynamicPropertyRegistry properties) {
		properties.add("mentalbridge.identity.jwt.issuer", () -> "https://identity.test.mentalbridge");
		properties.add("mentalbridge.identity.jwt.audience", () -> "mentalbridge-test-api");
		properties.add("mentalbridge.identity.jwt.key-id", () -> "test-key");
		properties.add("mentalbridge.identity.jwt.private-key",
				() -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPrivate().getEncoded()));
		properties.add("mentalbridge.identity.jwt.public-key",
				() -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
		properties.add("mentalbridge.identity.encryption.key-version", () -> "test-v1");
		properties.add("mentalbridge.identity.encryption.key",
				() -> Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
		properties.add("mentalbridge.identity.verification-delivery.base-url", () -> "https://api.brevo.com");
		properties.add("mentalbridge.identity.verification-delivery.api-key", () -> "test-brevo-key");
		properties.add("mentalbridge.identity.verification-delivery.sender-email", () -> "no-reply@example.test");
		properties.add("mentalbridge.identity.verification-delivery.sender-name", () -> "MentalBridge");
		properties.add("mentalbridge.identity.verification-delivery.verification-url",
				() -> "http://localhost:3000/verify-email");
		properties.add("mentalbridge.identity.verification-delivery.password-recovery-url",
				() -> "http://localhost:3000/reset-password");
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
