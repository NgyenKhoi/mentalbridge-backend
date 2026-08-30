package com.mentalbridge.care.shared;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ProblemDetail domain(ApiException exception, HttpServletRequest request) {
		return problem(exception.status(), exception.code(), exception.getMessage(), exception.violations(), request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail bodyValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
		var violations = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> new ApiException.FieldViolation(error.getField(), "INVALID_VALUE", error.getDefaultMessage()))
				.toList();
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", violations, request);
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ProblemDetail missingHeader(MissingRequestHeaderException exception, HttpServletRequest request) {
		if ("X-Anonymous-Session-Token".equalsIgnoreCase(exception.getHeaderName())) {
			return problem(HttpStatus.UNAUTHORIZED, "INVALID_ANONYMOUS_SESSION",
					"Anonymous assessment session is invalid", List.of(), request);
		}
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", List.of(), request);
	}

	@ExceptionHandler({ HandlerMethodValidationException.class, HttpMessageNotReadableException.class })
	ProblemDetail requestValidation(Exception exception, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", List.of(), request);
	}

	private ProblemDetail problem(HttpStatus status, String code, String title,
			List<ApiException.FieldViolation> violations, HttpServletRequest request) {
		var problem = ProblemDetail.forStatusAndDetail(status, title);
		problem.setTitle(title);
		problem.setType(URI.create("/problems/" + code.toLowerCase().replace('_', '-')));
		problem.setProperty("code", code);
		problem.setProperty("correlationId", correlationId(request));
		if (!violations.isEmpty()) {
			problem.setProperty("violations", violations);
		}
		return problem;
	}

	private UUID correlationId(HttpServletRequest request) {
		try {
			var supplied = request.getHeader("X-Correlation-Id");
			return supplied == null ? UUID.randomUUID() : UUID.fromString(supplied);
		}
		catch (IllegalArgumentException exception) {
			return UUID.randomUUID();
		}
	}
}
