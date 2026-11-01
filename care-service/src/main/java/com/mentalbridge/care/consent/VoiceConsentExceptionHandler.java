package com.mentalbridge.care.consent;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = VoiceConsentController.class)
public class VoiceConsentExceptionHandler {
	@ExceptionHandler(VoiceApiException.class)
	ResponseEntity<VoiceProblemSupport.Problem> domain(VoiceApiException exception, HttpServletRequest request) {
		return problem(request, exception.status().value(), exception.code(), exception.getMessage());
	}

	@ExceptionHandler({ MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
			HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
			MethodArgumentTypeMismatchException.class })
	ResponseEntity<VoiceProblemSupport.Problem> validation(Exception exception, HttpServletRequest request) {
		return problem(request, 422, "INVALID_REQUEST", "The voice request is invalid");
	}

	@ExceptionHandler({ DataAccessException.class, org.springframework.transaction.TransactionException.class })
	ResponseEntity<VoiceProblemSupport.Problem> unavailable(Exception exception, HttpServletRequest request) {
		return problem(request, 503, "CONSENT_UNAVAILABLE", "Current consent is unavailable");
	}

	private ResponseEntity<VoiceProblemSupport.Problem> problem(HttpServletRequest request, int status, String code,
			String title) {
		return ResponseEntity.status(status).header("Cache-Control", "no-store")
				.contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
				.body(VoiceProblemSupport.problem(request, status, code, title));
	}
}
