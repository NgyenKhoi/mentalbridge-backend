package com.mentalbridge.consultation.dashboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
	void operationsSummaryRequiresAdminRoleAndReturnsAuthoritativeAggregates() throws Exception {
		mvc.perform(get("/api/v1/admin/operations/summary"))
				.andExpect(status().isUnauthorized());

		mvc.perform(get("/api/v1/admin/operations/summary").with(user(UUID.randomUUID())))
				.andExpect(status().isForbidden());

		mvc.perform(get("/api/v1/admin/operations/summary").with(specialist(UUID.randomUUID())))
				.andExpect(status().isForbidden());

		mvc.perform(get("/api/v1/admin/operations/summary").with(admin(UUID.randomUUID())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("CONSULTATION"))
				.andExpect(jsonPath("$.asOf").exists())
				.andExpect(jsonPath("$.specialists").exists())
				.andExpect(jsonPath("$.appointments").exists())
				.andExpect(jsonPath("$.specialists.total").isNumber())
				.andExpect(jsonPath("$.appointments.total").isNumber())
				.andExpect(jsonPath("$.journal").doesNotExist())
				.andExpect(jsonPath("$.notes").doesNotExist())
				.andExpect(jsonPath("$.chatBody").doesNotExist());
	}

	private RequestPostProcessor admin(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("ADMIN")))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("USER")))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private RequestPostProcessor specialist(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("SPECIALIST")))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"));
	}
}
