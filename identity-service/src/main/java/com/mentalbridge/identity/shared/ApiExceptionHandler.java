package com.mentalbridge.identity.shared;

import java.net.URI;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import com.mentalbridge.identity.authentication.InvalidCredentialsException;
import com.mentalbridge.identity.authentication.InvalidSessionException;
import com.mentalbridge.identity.idempotency.IdempotencyConflictException;
import com.mentalbridge.identity.registration.InvalidVerificationChallengeException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request);
	}

	@ExceptionHandler({ HandlerMethodValidationException.class, MissingRequestHeaderException.class,
			HttpMessageNotReadableException.class })
	ProblemDetail requestValidation(Exception exception, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request);
	}

	@ExceptionHandler(InvalidVerificationChallengeException.class)
	ProblemDetail invalidChallenge(InvalidVerificationChallengeException exception, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_CHALLENGE", "Challenge is invalid", request);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail conflict(DataIntegrityViolationException exception, HttpServletRequest request) {
		var duplicateEmail = exception.getMostSpecificCause().getMessage().contains("ux_account_email");
		return problem(HttpStatus.CONFLICT, duplicateEmail ? "ACCOUNT_ALREADY_EXISTS" : "CONFLICT",
				duplicateEmail ? "Account registration conflicts with existing data" : "Request conflicts with current state",
				request);
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ProblemDetail invalidCredentials(InvalidCredentialsException exception, HttpServletRequest request) {
		return problem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Credentials are invalid", request);
	}

	@ExceptionHandler(InvalidSessionException.class)
	ProblemDetail invalidSession(InvalidSessionException exception, HttpServletRequest request) {
		return problem(HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "Session is invalid", request);
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	ProblemDetail idempotencyConflict(IdempotencyConflictException exception, HttpServletRequest request) {
		return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was reused", request);
	}

	private ProblemDetail problem(HttpStatus status, String code, String title, HttpServletRequest request) {
		var problem = ProblemDetail.forStatusAndDetail(status, title);
		problem.setTitle(title);
		problem.setType(URI.create("/problems/" + code.toLowerCase().replace('_', '-')));
		problem.setProperty("code", code);
		problem.setProperty("correlationId", correlationId(request));
		return problem;
	}

	private UUID correlationId(HttpServletRequest request) {
		var supplied = request.getHeader("X-Correlation-Id");
		try {
			return supplied == null ? UUID.randomUUID() : UUID.fromString(supplied);
		}
		catch (IllegalArgumentException exception) {
			return UUID.randomUUID();
		}
	}

}
