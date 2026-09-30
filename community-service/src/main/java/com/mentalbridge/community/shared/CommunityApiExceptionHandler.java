package com.mentalbridge.community.shared;

import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class CommunityApiExceptionHandler {

	@ExceptionHandler(CommunityApiException.class)
	ProblemDetail domain(CommunityApiException exception, HttpServletRequest request) {
		return problem(exception.status(), exception.code(), exception.getMessage(), request);
	}

	@ExceptionHandler({ HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
			MethodArgumentNotValidException.class })
	ProblemDetail validation(Exception exception, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MissingRequestHeaderException.class })
	ProblemDetail malformedRequest(Exception exception, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ProblemDetail optimisticLock(OptimisticLockingFailureException exception, HttpServletRequest request) {
		return problem(HttpStatus.PRECONDITION_FAILED, "COMMUNITY_POST_VERSION_MISMATCH",
				"Community post changed before this request completed", request);
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
		try {
			var supplied = request.getHeader("X-Correlation-Id");
			return supplied == null ? UUID.randomUUID() : UUID.fromString(supplied);
		}
		catch (IllegalArgumentException exception) {
			return UUID.randomUUID();
		}
	}

}
