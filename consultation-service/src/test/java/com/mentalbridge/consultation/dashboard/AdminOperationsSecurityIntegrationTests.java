package com.mentalbridge.consultation.dashboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminOperationsSecurityIntegrationTests extends ConsultationTestProperties {

	@Autowired
	MockMvc mvc;

	@Test
	void unauthenticatedRequestReturns401() throws Exception {
		mvc.perform(get("/api/v1/admin/operations/summary"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void userRoleReturns403() throws Exception {
		mvc.perform(get("/api/v1/admin/operations/summary").with(user(UUID.randomUUID())))
				.andExpect(status().isForbidden());
	}

	@Test
	void specialistRoleReturns403() throws Exception {
		mvc.perform(get("/api/v1/admin/operations/summary").with(specialist(UUID.randomUUID())))
				.andExpect(status().isForbidden());
	}

	@Test
	void adminRoleReturns200WithAuthoritativeSummary() throws Exception {
		mvc.perform(get("/api/v1/admin/operations/summary").with(admin(UUID.randomUUID())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("CONSULTATION"))
				.andExpect(jsonPath("$.asOf").isNotEmpty())
				.andExpect(jsonPath("$.specialists").isMap())
				.andExpect(jsonPath("$.appointments").isMap());
	}

	private RequestPostProcessor admin(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", java.util.List.of("ADMIN")))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private RequestPostProcessor specialist(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", java.util.List.of("SPECIALIST")))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"));
	}

	private RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", java.util.List.of("USER")))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}

