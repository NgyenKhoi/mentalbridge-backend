package com.mentalbridge.consultation.configuration;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mentalbridge.consultation.shared.SecurityProblemSupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class AccountStatusEnforcementFilter extends OncePerRequestFilter {
	private final IdentityAccountStatusVerifier verifier;
	private final SecurityProblemSupport problems;
	private final List<PathPattern> publicEndpoints;

	AccountStatusEnforcementFilter(IdentityAccountStatusVerifier verifier, SecurityProblemSupport problems,
			String... publicPaths) {
		this.verifier = verifier;
		this.problems = problems;
		this.publicEndpoints = Arrays.stream(publicPaths).map(PathPatternParser.defaultInstance::parse).toList();
	}

	@Override protected boolean shouldNotFilter(HttpServletRequest request) {
		var path = PathContainer.parsePath(request.getRequestURI());
		return publicEndpoints.stream().anyMatch(pattern -> pattern.matches(path));
	}

	@Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
			chain.doFilter(request, response);
			return;
		}
		try {
			if (!verifier.isActive(UUID.fromString(jwt.getToken().getSubject()), correlationId(request))) {
				SecurityContextHolder.clearContext();
				problems.invalidToken(request, response);
				return;
			}
		}
		catch (IllegalArgumentException | NullPointerException exception) {
			SecurityContextHolder.clearContext();
			problems.invalidToken(request, response);
			return;
		}
		catch (IdentityAccountStatusVerifier.IdentityAccountStatusUnavailableException exception) {
			SecurityContextHolder.clearContext();
			problems.authenticationDependencyUnavailable(request, response);
			return;
		}
		chain.doFilter(request, response);
	}

	private UUID correlationId(HttpServletRequest request) {
		try {
			var value = request.getHeader("X-Correlation-Id");
			return value == null ? UUID.randomUUID() : UUID.fromString(value);
		}
		catch (IllegalArgumentException exception) { return UUID.randomUUID(); }
	}
}
