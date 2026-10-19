package com.mentalbridge.consultation;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

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
		properties.add("mentalbridge.consultation.video.demo-immediate-access-enabled", () -> "false");
		properties.add("mentalbridge.consultation.payout.encryption-key",
				() -> Base64.getEncoder().encodeToString("test-payout-key-32-bytes-long!!!".getBytes()));
		properties.add("mentalbridge.consultation.payout.mode", () -> "FAKE");
		properties.add("mentalbridge.consultation.billing.mode", () -> "FAKE");
		properties.add("mentalbridge.consultation.billing.momo-partner-code", () -> "MOMO_TEST_PARTNER");
		properties.add("mentalbridge.consultation.billing.momo-access-key", () -> "test-payment-access-key");
		properties.add("mentalbridge.consultation.billing.momo-secret-key", () -> "test-payment-secret-key");
		properties.add("mentalbridge.consultation.billing.momo-signature-key-version", () -> "test-v1");
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

	protected static String signedTestToken(UUID subject, String role) throws Exception {
		if (!java.util.Set.of("USER", "SPECIALIST", "ADMIN").contains(role)) throw new IllegalArgumentException("Unknown test role");
		var encoder = Base64.getUrlEncoder().withoutPadding();
		var header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var claims = "{\"iss\":\"https://identity.test.mentalbridge\",\"aud\":[\"mentalbridge-test-api\"],\"sub\":\"%s\",\"roles\":[\"%s\"],\"exp\":%d}"
				.formatted(subject, role, java.time.Instant.now().plusSeconds(40 * 86_400L).getEpochSecond());
		var payload = header + "." + encoder.encodeToString(claims.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var signature = java.security.Signature.getInstance("SHA256withRSA");
		signature.initSign(JWT_KEY_PAIR.getPrivate());
		signature.update(payload.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		return payload + "." + encoder.encodeToString(signature.sign());
	}
}
