package com.mentalbridge.identity.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;

class Utf8ByteLengthValidatorTests {

	@Test
	void validatesUtf8BytesInsteadOfJavaCharacterCount() {
		try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
			var validator = validatorFactory.getValidator();

			assertThat(validator.validate(new PasswordInput("a".repeat(72)))).isEmpty();
			assertThat(validator.validate(new PasswordInput("🙂".repeat(19)))).hasSize(1);
		}
	}

	private record PasswordInput(@Utf8ByteLength(max = 72) String password) {
	}

}
