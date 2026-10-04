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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.mentalbridge.identity.account.AccountNotFoundException;
import com.mentalbridge.identity.account.AccountVersionMismatchException;
import com.mentalbridge.identity.account.DedicatedAdminProtectionException;
import com.mentalbridge.identity.account.InvalidStateTransitionException;
import com.mentalbridge.identity.account.InvalidAdminAccountQueryException;
import com.mentalbridge.identity.authentication.InvalidCredentialsException;
import com.mentalbridge.identity.authentication.InvalidSessionException;
import com.mentalbridge.identity.idempotency.IdempotencyConflictException;
import com.mentalbridge.identity.registration.InvalidVerificationChallengeException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, response);
	}

	@ExceptionHandler({ HandlerMethodValidationException.class, MissingRequestHeaderException.class,
			HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			InvalidAdminAccountQueryException.class })
	ProblemDetail requestValidation(Exception exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, response);
	}

	@ExceptionHandler(InvalidVerificationChallengeException.class)
	ProblemDetail invalidChallenge(InvalidVerificationChallengeException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_CHALLENGE", "Challenge is invalid", request, response);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail conflict(DataIntegrityViolationException exception, HttpServletRequest request,
			HttpServletResponse response) {
		var duplicateEmail = exception.getMostSpecificCause().getMessage().contains("ux_account_email");
		return problem(HttpStatus.CONFLICT, duplicateEmail ? "ACCOUNT_ALREADY_EXISTS" : "CONFLICT",
				duplicateEmail ? "Account registration conflicts with existing data" : "Request conflicts with current state",
				request, response);
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ProblemDetail invalidCredentials(InvalidCredentialsException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Credentials are invalid", request, response);
	}

	@ExceptionHandler(InvalidSessionException.class)
	ProblemDetail invalidSession(InvalidSessionException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "Session is invalid", request, response);
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	ProblemDetail idempotencyConflict(IdempotencyConflictException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key was reused", request, response);
	}

	@ExceptionHandler(AccountNotFoundException.class)
	ProblemDetail accountNotFound(AccountNotFoundException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request, response);
	}

	@ExceptionHandler(DedicatedAdminProtectionException.class)
	ProblemDetail dedicatedAdminProtection(DedicatedAdminProtectionException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage(), request, response);
	}

	@ExceptionHandler(AccountVersionMismatchException.class)
	ProblemDetail versionMismatch(AccountVersionMismatchException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.PRECONDITION_FAILED, "VERSION_CONFLICT", exception.getMessage(), request, response);
	}

	@ExceptionHandler(InvalidStateTransitionException.class)
	ProblemDetail invalidStateTransition(InvalidStateTransitionException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request, response);
	}

	private ProblemDetail problem(HttpStatus status, String code, String title, HttpServletRequest request,
			HttpServletResponse response) {
		var problem = ProblemDetail.forStatusAndDetail(status, title);
		problem.setTitle(title);
		problem.setType(URI.create("/problems/" + code.toLowerCase().replace('_', '-')));
		problem.setProperty("code", code);
		UUID correlationId = correlationId(request);
		problem.setProperty("correlationId", correlationId);
		if (response != null) {
			response.setHeader("X-Correlation-Id", correlationId.toString());
		}
		return problem;
	}

	private UUID correlationId(HttpServletRequest request) {
		if (request != null) {
			var attribute = request.getAttribute("correlationId");
			if (attribute instanceof UUID uuid) {
				return uuid;
			}
			if (attribute instanceof String str && !str.isBlank()) {
				try {
					return UUID.fromString(str);
				}
				catch (IllegalArgumentException ignored) {
				}
			}
			var supplied = request.getHeader("X-Correlation-Id");
			try {
				if (supplied != null && !supplied.isBlank()) {
					return UUID.fromString(supplied);
				}
			}
			catch (IllegalArgumentException ignored) {
			}
		}
		return UUID.randomUUID();
	}

}
