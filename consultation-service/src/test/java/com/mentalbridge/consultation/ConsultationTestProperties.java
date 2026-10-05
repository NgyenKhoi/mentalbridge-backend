package com.mentalbridge.consultation;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class ConsultationTestProperties {

	protected static final String EVIDENCE_SERVICE_TOKEN = "test-consultation-evidence-token-32-characters";
	private static final KeyPair JWT_KEY_PAIR = keyPair();

	static {
		System.setProperty("user.timezone", "UTC");
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
	}

	@DynamicPropertySource
	static void consultationProperties(DynamicPropertyRegistry properties) {
		properties.add("mentalbridge.consultation.jwt.issuer", () -> "https://identity.test.mentalbridge");
		properties.add("mentalbridge.consultation.jwt.audience", () -> "mentalbridge-test-api");
		properties.add("mentalbridge.consultation.jwt.public-key",
				() -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
		properties.add("mentalbridge.consultation.evidence-service-token", () -> EVIDENCE_SERVICE_TOKEN);
		properties.add("mentalbridge.consultation.account-lifecycle.enabled", () -> "false");
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
