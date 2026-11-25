package com.mentalbridge.consultation.entitlement;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class VoiceEntitlementAuthenticationFilter extends OncePerRequestFilter {

	public static final String PATH = "/internal/v1/ai-companion/voice-entitlement-authorizations";
	private final VoiceEntitlementProperties properties;
	private final ObjectMapper json = new ObjectMapper();

	public VoiceEntitlementAuthenticationFilter(VoiceEntitlementProperties properties) {
		this.properties = properties;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader("Cache-Control", "no-store");
		var tokens = java.util.Collections.list(request.getHeaders("X-MentalBridge-Voice-Entitlement-Token"));
		if (tokens.size() != 1 || !properties.authenticates(tokens.getFirst())) {
			writeProblem(request, response, 401, "UNAUTHENTICATED", "Journal service authentication is required");
			return;
		}
		if (request.getHeader("Authorization") != null) {
			writeProblem(request, response, 403, "FORBIDDEN", "A dedicated Journal service credential is required");
			return;
		}
		if (!"POST".equals(request.getMethod()) || !hasJsonContentType(request)) {
			writeProblem(request, response, 422, "INVALID_REQUEST", "The voice entitlement request is invalid");
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean hasJsonContentType(HttpServletRequest request) {
		try {
			var contentType = MediaType.parseMediaType(request.getContentType());
			return "application".equals(contentType.getType()) && "json".equals(contentType.getSubtype());
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	public static Map<String, Object> problem(HttpServletRequest request, int status, String code, String title) {
		UUID correlation;
		try {
			correlation = UUID.fromString(request.getHeader("X-Correlation-Id"));
		}
		catch (IllegalArgumentException | NullPointerException exception) {
			correlation = UUID.randomUUID();
		}
		return Map.of("type", "https://mentalbridge.example/problems/" + code.toLowerCase().replace('_', '-'),
				"title", title, "status", status, "code", code, "correlationId", correlation.toString());
	}

	private void writeProblem(HttpServletRequest request, HttpServletResponse response, int status, String code,
			String title) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		json.writeValue(response.getOutputStream(), problem(request, status, code, title));
	}
}
