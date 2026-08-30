package com.mentalbridge.care.shared;

import java.util.List;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final List<FieldViolation> violations;

	public ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, List.of());
	}

	public ApiException(HttpStatus status, String code, String message, List<FieldViolation> violations) {
		super(message);
		this.status = status;
		this.code = code;
		this.violations = List.copyOf(violations);
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public List<FieldViolation> violations() {
		return violations;
	}

	public record FieldViolation(String field, String code, String message) {
	}
}
