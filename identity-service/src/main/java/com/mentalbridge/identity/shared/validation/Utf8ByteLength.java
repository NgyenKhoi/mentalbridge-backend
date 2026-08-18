package com.mentalbridge.identity.shared.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Documented
@Constraint(validatedBy = Utf8ByteLengthValidator.class)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE })
@Retention(RUNTIME)
public @interface Utf8ByteLength {

	String message() default "must not exceed the allowed UTF-8 byte length";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	int max();

}
