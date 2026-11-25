package com.mentalbridge.identity.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.account.AccountStatusAuthorizationService;
import com.mentalbridge.identity.shared.SecurityProblemSupport;

class LocalAccountStatusEnforcementFilterTests {
	private final AccountStatusAuthorizationService authorization = mock(AccountStatusAuthorizationService.class);
	private final LocalAccountStatusEnforcementFilter filter = new LocalAccountStatusEnforcementFilter(authorization,
			new SecurityProblemSupport(new ObjectMapper()));

	@AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

	@Test
	void activeUserAdminAndSpecialistContinueWithoutAnHttpDependency() throws Exception {
		for (var role : List.of("USER", "ADMIN", "SPECIALIST")) {
			var accountId = UUID.randomUUID();
			when(authorization.isActive(accountId)).thenReturn(true);
			authenticate(accountId.toString(), role);
			var chain = new MockFilterChain();
			filter.doFilterInternal(request("/api/v1/account"), new MockHttpServletResponse(), chain);
			assertThat(chain.getRequest()).isNotNull();
		}
	}

	@Test
	void deletedUnknownOrNonActiveAccountReturnsInvalidToken() throws Exception {
		var accountId = UUID.randomUUID();
		when(authorization.isActive(accountId)).thenReturn(false);
		authenticate(accountId.toString(), "USER");
		var response = new MockHttpServletResponse();
		filter.doFilterInternal(request("/api/v1/account"), response, new MockFilterChain());
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("invalid_token");
	}

	@Test
	void localPersistenceFailureFailsClosedAsDependencyUnavailable() throws Exception {
		var accountId = UUID.randomUUID();
		when(authorization.isActive(accountId)).thenThrow(new IllegalStateException("database unavailable"));
		authenticate(accountId.toString(), "ADMIN");
		var response = new MockHttpServletResponse();
		filter.doFilterInternal(request("/api/v1/admin/accounts"), response, new MockFilterChain());
		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentAsString()).contains("authentication_dependency_unavailable");
	}

	@Test
	void publicAuthenticationEndpointsDoNotPerformAccountLookup() throws Exception {
		authenticate(UUID.randomUUID().toString(), "USER");
		var chain = new MockFilterChain();
		filter.doFilter(request("/api/v1/auth/login"), new MockHttpServletResponse(), chain);
		assertThat(chain.getRequest()).isNotNull();
		verifyNoInteractions(authorization);
	}

	private void authenticate(String subject, String role) {
		var jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(subject)
				.claim("roles", List.of(role)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
		SecurityContextHolder.getContext().setAuthentication(
				new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
	}

	private MockHttpServletRequest request(String path) {
		return new MockHttpServletRequest("GET", path);
	}
}
