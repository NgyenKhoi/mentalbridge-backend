package com.mentalbridge.identity.configuration;

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

import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.shared.SecurityProblemSupport;

@WebMvcTest(AccountStatusSecurityChainTests.TestController.class)
@ContextConfiguration(classes = { SecurityConfiguration.class, SecurityProblemSupport.class,
		AccountStatusSecurityChainTests.TestController.class })
@EnableConfigurationProperties(JwtProperties.class)
class AccountStatusSecurityChainTests extends IdentityTestProperties {
	private static final String PROTECTED = "/api/v1/account/status-test";
	@Autowired MockMvc mvc;

	@Test
	void activeJwtIsAllowedThroughTheRealSecurityChain() throws Exception {
		when(accountStatusAuthorization.isActive(any())).thenReturn(true);
		mvc.perform(get(PROTECTED).with(user(UUID.randomUUID()))).andExpect(status().isOk());
	}

	@Test
	void deletedOrUnknownAccountReturnsInvalidToken() throws Exception {
		when(accountStatusAuthorization.isActive(any())).thenReturn(false);
		mvc.perform(get(PROTECTED).with(user(UUID.randomUUID()))).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("invalid_token"));
	}

	@Test
	void localAccountDependencyFailureReturns503() throws Exception {
		when(accountStatusAuthorization.isActive(any())).thenThrow(new IllegalStateException("unavailable"));
		mvc.perform(get(PROTECTED).with(user(UUID.randomUUID()))).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("authentication_dependency_unavailable"));
	}

	@Test
	void publicAuthenticationEndpointDoesNotCheckAccountStatus() throws Exception {
		mvc.perform(get("/api/v1/auth/login")).andExpect(status().isOk());
		verifyNoInteractions(accountStatusAuthorization);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID accountId) {
		return jwt().jwt(token -> token.subject(accountId.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	@RestController
	public static class TestController {
		@GetMapping({ PROTECTED, "/api/v1/auth/login" })
		String ok() { return "ok"; }
	}
}
