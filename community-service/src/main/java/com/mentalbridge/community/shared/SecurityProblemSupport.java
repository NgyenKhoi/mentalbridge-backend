package com.mentalbridge.community.shared;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class SecurityProblemSupport implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	public SecurityProblemSupport(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authenticationException) throws IOException {
		write(response, request, 401, "UNAUTHENTICATED", "Authentication is required");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			org.springframework.security.access.AccessDeniedException accessDeniedException) throws IOException {
		write(response, request, 403, "COMMUNITY_USER_REQUIRED", "A USER account is required");
	}

	private void write(HttpServletResponse response, HttpServletRequest request, int status, String code, String title)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), Map.of(
				"type", "/problems/" + code.toLowerCase().replace('_', '-'),
				"title", title,
				"status", status,
				"code", code,
				"correlationId", correlationId(request).toString()));
	}

	private UUID correlationId(HttpServletRequest request) {
		try {
			var value = request.getHeader("X-Correlation-Id");
			return value == null ? UUID.randomUUID() : UUID.fromString(value);
		}
		catch (IllegalArgumentException exception) {
			return UUID.randomUUID();
		}
	}

}
