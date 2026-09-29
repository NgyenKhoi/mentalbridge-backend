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
