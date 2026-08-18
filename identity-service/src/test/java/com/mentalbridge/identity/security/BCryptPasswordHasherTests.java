package com.mentalbridge.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BCryptPasswordHasherTests {

	private final BCryptPasswordHasher hasher = new BCryptPasswordHasher();

	@Test
	void hashesAndMatchesPasswordWithConfiguredCost() {
		var encoded = hasher.hash("correct-horse-battery-staple");

		assertThat(encoded).startsWith("$2a$12$");
		assertThat(hasher.matches("correct-horse-battery-staple", encoded)).isTrue();
		assertThat(hasher.matches("wrong-password", encoded)).isFalse();
	}

	@Test
	void rejectsOverlongLoginInputAsCredentialMismatch() {
		var encoded = hasher.hash("correct-horse-battery-staple");

		assertThat(hasher.matches("a".repeat(73), encoded)).isFalse();
	}

}
