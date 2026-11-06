package com.mentalbridge.care.consent;

import java.io.IOException;
import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConsentResponseCacheFilter extends OncePerRequestFilter {
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		var requestPath = request.getRequestURI().substring(request.getContextPath().length());
		return List.of("/api/v1/consents", "/api/v1/consent-decisions", "/api/v1/privacy-disclosures",
				"/api/v1/ai-processing-disclosures", "/api/v1/ai-context-processing-disclosures").stream()
				.noneMatch(path -> requestPath.equals(path) || requestPath.startsWith(path + "/"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader("Cache-Control", "no-store");
		chain.doFilter(request, response);
	}
}
