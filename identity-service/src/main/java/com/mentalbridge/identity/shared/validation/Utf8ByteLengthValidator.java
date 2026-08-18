package com.mentalbridge.identity.shared.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class Utf8ByteLengthValidator implements ConstraintValidator<Utf8ByteLength, CharSequence> {

	private int max;

	@Override
	public void initialize(Utf8ByteLength constraint) {
		max = constraint.max();
	}

	@Override
	public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
		return value == null || value.toString().getBytes(StandardCharsets.UTF_8).length <= max;
	}

}
