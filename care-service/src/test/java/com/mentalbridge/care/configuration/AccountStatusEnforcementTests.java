package com.mentalbridge.care.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
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
import com.mentalbridge.care.shared.SecurityProblemSupport;

class AccountStatusEnforcementTests {
	private static final String TOKEN = "synthetic-care-account-status-token-123";

	@AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

	@Test
	void activeUserContinuesAndInactiveUserReturnsInvalidToken() throws Exception {
		var verifier = mock(IdentityAccountStatusVerifier.class);
		var accountId = UUID.randomUUID();
		authenticate(accountId, "USER");
		when(verifier.isActive(accountId, accountId)).thenReturn(true);
		var activeChain = new MockFilterChain();
		filter(verifier).doFilterInternal(request("/api/v1/profile", accountId), new MockHttpServletResponse(), activeChain);
		assertThat(activeChain.getRequest()).isNotNull();

		when(verifier.isActive(accountId, accountId)).thenReturn(false);
		var response = new MockHttpServletResponse();
		filter(verifier).doFilterInternal(request("/api/v1/profile", accountId), response, new MockFilterChain());
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("invalid_token");
	}

	@Test
	void unavailableIdentityReturns503AndPublicEndpointBypassesLookup() throws Exception {
		var verifier = mock(IdentityAccountStatusVerifier.class);
		var accountId = UUID.randomUUID();
		authenticate(accountId, "USER");
		when(verifier.isActive(accountId, accountId))
				.thenThrow(new IdentityAccountStatusVerifier.IdentityAccountStatusUnavailableException());
		var response = new MockHttpServletResponse();
		filter(verifier).doFilterInternal(request("/api/v1/profile", accountId), response, new MockFilterChain());
		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentAsString()).contains("authentication_dependency_unavailable");

		var publicVerifier = mock(IdentityAccountStatusVerifier.class);
		var chain = new MockFilterChain();
		filter(publicVerifier).doFilter(request("/api/v1/questionnaires/current", accountId),
				new MockHttpServletResponse(), chain);
		assertThat(chain.getRequest()).isNotNull();
		verifyNoInteractions(publicVerifier);
	}

	@Test
	void verifierUsesDedicatedCallerIdentityAndRejectsMalformedOrFailedResponses() {
		var client = mock(IdentityAccountStatusHttpClient.class);
		var properties = new IdentityAccountStatusProperties("http://identity", TOKEN,
				Duration.ofMillis(250), Duration.ofMillis(750));
		var verifier = new IdentityAccountStatusVerifier(client, properties);
		var accountId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		when(client.authorize(accountId, "care-service", TOKEN, correlationId.toString()))
				.thenReturn(new IdentityAccountStatusHttpClient.Result(true));
		assertThat(verifier.isActive(accountId, correlationId)).isTrue();

		when(client.authorize(accountId, "care-service", TOKEN, correlationId.toString())).thenReturn(null);
		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> verifier.isActive(accountId, correlationId)))
				.isInstanceOf(IdentityAccountStatusVerifier.IdentityAccountStatusUnavailableException.class);

		when(client.authorize(accountId, "care-service", TOKEN, correlationId.toString()))
				.thenThrow(new IllegalStateException("timeout"));
		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> verifier.isActive(accountId, correlationId)))
				.isInstanceOf(IdentityAccountStatusVerifier.IdentityAccountStatusUnavailableException.class);
		var missingCredential = new IdentityAccountStatusVerifier(client,
				new IdentityAccountStatusProperties("http://identity", "", Duration.ofMillis(250), Duration.ofMillis(750)));
		assertThat(org.assertj.core.api.Assertions.catchThrowable(
				() -> missingCredential.isActive(accountId, correlationId)))
				.isInstanceOf(IdentityAccountStatusVerifier.IdentityAccountStatusUnavailableException.class);
	}

	private AccountStatusEnforcementFilter filter(IdentityAccountStatusVerifier verifier) {
		return new AccountStatusEnforcementFilter(verifier, new SecurityProblemSupport(new ObjectMapper()),
				"/api/v1/questionnaires/**");
	}

	private void authenticate(UUID subject, String role) {
		var jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(subject.toString())
				.claim("roles", List.of(role)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
		SecurityContextHolder.getContext().setAuthentication(
				new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
	}

	private MockHttpServletRequest request(String path, UUID correlationId) {
		var request = new MockHttpServletRequest("GET", path);
		request.addHeader("X-Correlation-Id", correlationId.toString());
		return request;
	}
}
