package com.mentalbridge.consultation.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.configuration.ConsultationJwtProperties;
import com.mentalbridge.consultation.configuration.SecurityConfiguration;
import com.mentalbridge.consultation.shared.SecurityProblemSupport;

@WebMvcTest(AdminOperationsController.class)
@Import({ SecurityConfiguration.class, SecurityProblemSupport.class })
@EnableConfigurationProperties(ConsultationJwtProperties.class)
class AdminOperationsSecurityIntegrationTests extends ConsultationTestProperties {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	AdminOperationsService service;

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
		var now = Instant.parse("2026-10-05T12:00:00Z");
		var specialists = new AdminOperationsResponse.AdminSpecialistOperationsSummary(
				10, 2, 7, 1, 0);
		var appointments = new AdminOperationsResponse.AdminAppointmentOperationsSummary(
				25, 3, 8, 2, 1, 7, 2, 1, 1, 1, 1, 0);
		var response = new AdminOperationsResponse("CONSULTATION", now, specialists, appointments);

		when(service.getOperationsSummary()).thenReturn(response);

		mvc.perform(get("/api/v1/admin/operations/summary").with(admin(UUID.randomUUID())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("CONSULTATION"))
				.andExpect(jsonPath("$.asOf").value("2026-10-05T12:00:00Z"))
				.andExpect(jsonPath("$.specialists.total").value(10))
				.andExpect(jsonPath("$.appointments.total").value(25))
				.andExpect(jsonPath("$.appointments.userNoShow").value(1))
				.andExpect(jsonPath("$.appointments.specialistNoShow").value(1))
				.andExpect(jsonPath("$.appointments.bothNoShow").value(0))
				.andExpect(jsonPath("$.appointments.disputed").doesNotExist());
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

