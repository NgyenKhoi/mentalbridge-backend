package com.mentalbridge.identity.reporting;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestControllerAdvice
class PlatformReportExceptionHandler {

	@ExceptionHandler(InvalidPlatformReportRequestException.class)
	ProblemDetail invalid(InvalidPlatformReportRequestException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request, response);
	}

	@ExceptionHandler(PlatformReportNotFoundException.class)
	ProblemDetail missing(PlatformReportNotFoundException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.NOT_FOUND, "PLATFORM_REPORT_NOT_FOUND", exception.getMessage(), request, response);
	}

	@ExceptionHandler(PlatformReportNotDownloadableException.class)
	ProblemDetail unavailable(PlatformReportNotDownloadableException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.CONFLICT, "PLATFORM_REPORT_NOT_COMPLETED", exception.getMessage(), request, response);
	}

	@ExceptionHandler(PlatformReportArtifactExpiredException.class)
	ProblemDetail expired(PlatformReportArtifactExpiredException exception, HttpServletRequest request,
			HttpServletResponse response) {
		return problem(HttpStatus.GONE, "PLATFORM_REPORT_ARTIFACT_EXPIRED", exception.getMessage(), request, response);
	}

	private ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request,
			HttpServletResponse response) {
		var problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(detail);
		problem.setType(URI.create("/problems/" + code.toLowerCase().replace('_', '-')));
		problem.setProperty("code", code);
		UUID correlationId = correlationId(request);
		problem.setProperty("correlationId", correlationId);
		response.setHeader("X-Correlation-Id", correlationId.toString());
		return problem;
	}

	private UUID correlationId(HttpServletRequest request) {
		try {
			String supplied = request.getHeader("X-Correlation-Id");
			return supplied == null || supplied.isBlank() ? UUID.randomUUID() : UUID.fromString(supplied);
		}
		catch (IllegalArgumentException exception) {
			return UUID.randomUUID();
		}
	}

}
