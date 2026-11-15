package com.mentalbridge.care.consent;

import java.net.URI;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice(assignableTypes = ConsentController.class)
public class ConsentReadExceptionHandler {
	@ExceptionHandler({ DataAccessException.class, TransactionException.class })
	ProblemDetail unavailable(RuntimeException exception, HttpServletRequest request) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Current consent authority is unavailable");
		problem.setType(URI.create("/problems/consent-unavailable"));
		problem.setProperty("code", "CONSENT_UNAVAILABLE");
		UUID correlationId;
		try { correlationId = UUID.fromString(request.getHeader("X-Correlation-Id")); }
		catch (IllegalArgumentException | NullPointerException invalid) { correlationId = UUID.randomUUID(); }
		problem.setProperty("correlationId", correlationId);
		return problem;
	}
}
