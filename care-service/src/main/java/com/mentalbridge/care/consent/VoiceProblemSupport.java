package com.mentalbridge.care.consent;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class VoiceProblemSupport implements AuthenticationEntryPoint, AccessDeniedHandler {
	private final ObjectMapper mapper;

	public VoiceProblemSupport(ObjectMapper mapper) { this.mapper = mapper; }

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		write(request, response, 401, "UNAUTHENTICATED", "Authentication is required");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			org.springframework.security.access.AccessDeniedException exception) throws IOException {
		write(request, response, 403, "FORBIDDEN", "A USER account is required");
	}

	private void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String title)
			throws IOException {
		response.setStatus(status);
		response.setHeader("Cache-Control", "no-store");
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		mapper.writeValue(response.getOutputStream(), problem(request, status, code, title));
	}

	static Problem problem(HttpServletRequest request, int status, String code, String title) {
		UUID correlationId;
		try { correlationId = UUID.fromString(request.getHeader("X-Correlation-Id")); }
		catch (IllegalArgumentException | NullPointerException exception) { correlationId = UUID.randomUUID(); }
		return new Problem("https://mentalbridge.io/errors/" + code, title, status, code, correlationId);
	}

	public void invalidRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
		write(request, response, 422, "INVALID_REQUEST", "The voice request is invalid");
	}

	public record Problem(String type, String title, int status, String code, UUID correlationId) { }
}
