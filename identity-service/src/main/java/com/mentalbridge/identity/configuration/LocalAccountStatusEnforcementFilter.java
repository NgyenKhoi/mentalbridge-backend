package com.mentalbridge.identity.configuration;

import java.io.IOException;
import java.util.UUID;
import java.util.List;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.mentalbridge.identity.account.AccountStatusAuthorizationService;
import com.mentalbridge.identity.shared.SecurityProblemSupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class LocalAccountStatusEnforcementFilter extends OncePerRequestFilter {

	private final AccountStatusAuthorizationService authorization;
	private final SecurityProblemSupport problems;
	private final List<PathPattern> publicEndpoints = List.of("/actuator/health", "/actuator/health/**",
			"/internal/v1/notification-delivery-contacts/**", "/internal/v1/accounts/*/verified-email",
			"/internal/v1/accounts/*/authorization", "/internal/v1/community-chat/account-authorizations",
			"/api/v1/auth/registrations", "/api/v1/auth/email-verifications",
			"/api/v1/auth/email-verification-requests", "/api/v1/auth/login", "/api/v1/auth/refresh",
			"/api/v1/auth/password-recovery-requests", "/api/v1/auth/password-resets").stream()
			.map(PathPatternParser.defaultInstance::parse).toList();

	LocalAccountStatusEnforcementFilter(AccountStatusAuthorizationService authorization,
			SecurityProblemSupport problems) {
		this.authorization = authorization;
		this.problems = problems;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		var path = PathContainer.parsePath(request.getRequestURI());
		return publicEndpoints.stream().anyMatch(pattern -> pattern.matches(path));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
			chain.doFilter(request, response);
			return;
		}
		try {
			var accountId = UUID.fromString(jwt.getToken().getSubject());
			if (!authorization.isActive(accountId)) {
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
		catch (RuntimeException exception) {
			SecurityContextHolder.clearContext();
			problems.authenticationDependencyUnavailable(request, response);
			return;
		}
		chain.doFilter(request, response);
	}
}
