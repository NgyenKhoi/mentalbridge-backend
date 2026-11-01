package com.mentalbridge.consultation.entitlement;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = VoiceEntitlementController.class)
public class VoiceEntitlementExceptionHandler {

	@ExceptionHandler({ VoiceEntitlementController.InvalidRequest.class, HttpMessageNotReadableException.class,
			HttpMediaTypeNotSupportedException.class, HttpRequestMethodNotSupportedException.class })
	ResponseEntity<Map<String, Object>> invalid(Exception exception, HttpServletRequest request) {
		return problem(request, 422, "INVALID_REQUEST", "The voice entitlement request is invalid");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, Object>> unavailable(Exception exception, HttpServletRequest request) {
		return problem(request, 503, "ENTITLEMENT_UNAVAILABLE", "Current entitlement is unavailable");
	}

	private ResponseEntity<Map<String, Object>> problem(HttpServletRequest request, int status, String code,
			String title) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.header("Cache-Control", "no-store")
				.body(VoiceEntitlementAuthenticationFilter.problem(request, status, code, title));
	}
}
