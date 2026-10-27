package com.mentalbridge.care.configuration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.shared.SecurityProblemSupport;

@WebMvcTest(AccountStatusSecurityChainTests.TestController.class)
@ContextConfiguration(classes = { SecurityConfiguration.class, SecurityProblemSupport.class,
		AccountStatusSecurityChainTests.TestController.class })
@EnableConfigurationProperties(CareJwtProperties.class)
class AccountStatusSecurityChainTests extends CareTestProperties {
	private static final String PROTECTED = "/api/v1/profile/status-test";
	@Autowired MockMvc mvc;

	@Test
	void activeJwtIsAllowedThroughTheRealSecurityChain() throws Exception {
		when(accountStatusVerifier.isActive(any(), any())).thenReturn(true);
		mvc.perform(get(PROTECTED).with(user(UUID.randomUUID()))).andExpect(status().isOk());
	}

	@Test
	void deletedOrUnknownAccountReturnsInvalidToken() throws Exception {
		when(accountStatusVerifier.isActive(any(), any())).thenReturn(false);
		mvc.perform(get(PROTECTED).with(user(UUID.randomUUID()))).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("invalid_token"));
	}

	@Test
	void identityDependencyFailureReturns503() throws Exception {
		when(accountStatusVerifier.isActive(any(), any()))
				.thenThrow(new IdentityAccountStatusVerifier.IdentityAccountStatusUnavailableException());
		mvc.perform(get(PROTECTED).with(user(UUID.randomUUID()))).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("authentication_dependency_unavailable"));
	}

	@Test
	void publicEndpointDoesNotCallIdentity() throws Exception {
		mvc.perform(get("/api/v1/questionnaires/status-test")).andExpect(status().isOk());
		verifyNoInteractions(accountStatusVerifier);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID accountId) {
		return jwt().jwt(token -> token.subject(accountId.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	@RestController
	public static class TestController {
		@GetMapping({ PROTECTED, "/api/v1/questionnaires/status-test" })
		String ok() { return "ok"; }
	}
}
