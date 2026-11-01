package com.mentalbridge.consultation.configuration;

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
import com.mentalbridge.consultation.shared.SecurityProblemSupport;

class AccountStatusEnforcementTests {
	@AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

	@Test
	void activeSpecialistContinuesWhileInactiveSpecialistIsRejected() throws Exception {
		var verifier = mock(IdentityAccountStatusVerifier.class);
		var accountId = UUID.randomUUID();
		authenticate(accountId, "SPECIALIST");
		when(verifier.isActive(accountId, accountId)).thenReturn(true);
		var filter = filter(verifier);
		var chain = new MockFilterChain();
		filter.doFilterInternal(request("/api/v1/specialist/dashboard", accountId), new MockHttpServletResponse(), chain);
		assertThat(chain.getRequest()).isNotNull();

		when(verifier.isActive(accountId, accountId)).thenReturn(false);
		var response = new MockHttpServletResponse();
		filter.doFilterInternal(request("/api/v1/specialist/dashboard", accountId), response, new MockFilterChain());
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("invalid_token");
	}

	@Test
	void dependencyFailureReturns503AndWebhookBypassesLookup() throws Exception {
		var verifier = mock(IdentityAccountStatusVerifier.class);
		var accountId = UUID.randomUUID();
		authenticate(accountId, "SPECIALIST");
		when(verifier.isActive(accountId, accountId))
				.thenThrow(new IdentityAccountStatusVerifier.IdentityAccountStatusUnavailableException());
		var response = new MockHttpServletResponse();
		filter(verifier).doFilterInternal(request("/api/v1/specialist/dashboard", accountId), response,
				new MockFilterChain());
		assertThat(response.getStatus()).isEqualTo(503);

		var publicVerifier = mock(IdentityAccountStatusVerifier.class);
		var chain = new MockFilterChain();
		filter(publicVerifier).doFilter(request("/internal/v1/video/livekit/webhook", accountId),
				new MockHttpServletResponse(), chain);
		assertThat(chain.getRequest()).isNotNull();
		verifyNoInteractions(publicVerifier);
	}

	private AccountStatusEnforcementFilter filter(IdentityAccountStatusVerifier verifier) {
		return new AccountStatusEnforcementFilter(verifier, new SecurityProblemSupport(new ObjectMapper()),
				"/internal/v1/video/livekit/webhook");
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
