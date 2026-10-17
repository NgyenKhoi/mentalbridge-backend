package com.mentalbridge.community;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class CommunityTestProperties {

	private static final KeyPair JWT_KEY_PAIR = keyPair();

	static KeyPair jwtKeyPair() {
		return JWT_KEY_PAIR;
	}

	@DynamicPropertySource
	static void communityProperties(DynamicPropertyRegistry properties) {
		properties.add("mentalbridge.community.jwt.issuer", () -> "https://identity.test.mentalbridge");
		properties.add("mentalbridge.community.jwt.audience", () -> "mentalbridge-test-api");
		properties.add("mentalbridge.community.jwt.public-key",
				() -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
		properties.add("mentalbridge.community.cloudinary.cloud-name", () -> "test-cloud");
		properties.add("mentalbridge.community.cloudinary.api-key", () -> "test-api-key");
		properties.add("mentalbridge.community.cloudinary.api-secret", () -> "test-api-secret");
		properties.add("mentalbridge.community.cloudinary.timeout-seconds", () -> "5");
		properties.add("mentalbridge.community.media.max-image-bytes", () -> "10485760");
		properties.add("mentalbridge.community.media.max-video-bytes", () -> "52428800");
		properties.add("mentalbridge.community.media.max-video-duration-seconds", () -> "60");
		properties.add("mentalbridge.community.media.upload-intent-ttl", () -> "10m");
		properties.add("mentalbridge.community.media.orphan-retention", () -> "24h");
		properties.add("mentalbridge.community.media.cleanup-interval", () -> "1h");
		properties.add("mentalbridge.community.interaction-relay.enabled", () -> "false");
		properties.add("mentalbridge.community.interaction-relay.topic",
				() -> "mentalbridge.community.interaction.v1");
		properties.add("mentalbridge.community.interaction-relay.batch-size", () -> "100");
		properties.add("mentalbridge.community.interaction-relay.interval", () -> "5s");
		properties.add("mentalbridge.community.interaction-relay.send-timeout", () -> "5s");
		properties.add("mentalbridge.community.interaction-relay.retry-base", () -> "5s");
		properties.add("mentalbridge.community.interaction-relay.retry-maximum", () -> "5m");
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
