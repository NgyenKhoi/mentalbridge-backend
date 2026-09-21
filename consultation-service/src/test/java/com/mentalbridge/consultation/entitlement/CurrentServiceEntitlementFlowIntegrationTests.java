package com.mentalbridge.consultation.entitlement;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CurrentServiceEntitlementFlowIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;

	@Test
	void returnsDefaultFreeWhenNoEffectiveProjectionExists() throws Exception {
		var userId = UUID.randomUUID();

		mvc.perform(get("/internal/v1/entitlements/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(userId.toString()))
				.andExpect(jsonPath("$.packageCode").value("FREE"))
				.andExpect(jsonPath("$.source").value("DEFAULT_FREE"))
				.andExpect(jsonPath("$.sourceReference").isEmpty())
				.andExpect(jsonPath("$.effectiveFrom").isEmpty())
				.andExpect(jsonPath("$.effectiveUntil").isEmpty())
				.andExpect(jsonPath("$.policyVersion").value("service-entitlement-v1"))
				.andExpect(jsonPath("$.version").value(0));
	}

	@Test
	void returnsOnlyTheAuthenticatedUsersEffectiveDemoProjection() throws Exception {
		var userId = UUID.randomUUID();
		var otherId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		insert(userId, "PREMIUM", "DEMO", "mb369-premium-demo", adminId,
				OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now().plusDays(7));

		mvc.perform(get("/internal/v1/entitlements/current?packageCode=FREE").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.packageCode").value("PREMIUM"))
				.andExpect(jsonPath("$.source").value("DEMO"))
				.andExpect(jsonPath("$.sourceReference").value("mb369-premium-demo"));
		mvc.perform(get("/internal/v1/entitlements/current").with(user(otherId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.packageCode").value("FREE"));
	}

	@Test
	void expiredProjectionFallsBackToFreeAndNonUserRolesAreRejected() throws Exception {
		var userId = UUID.randomUUID();
		insert(userId, "PLUS", "DEMO", "expired-demo", UUID.randomUUID(),
				OffsetDateTime.now().minusDays(2), OffsetDateTime.now().minusDays(1));

		mvc.perform(get("/internal/v1/entitlements/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.packageCode").value("FREE"))
				.andExpect(jsonPath("$.source").value("DEFAULT_FREE"));
		mvc.perform(get("/internal/v1/entitlements/current").with(role(userId, "ROLE_SPECIALIST")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/internal/v1/entitlements/current"))
				.andExpect(status().isUnauthorized());
	}

	private void insert(UUID accountId, String packageCode, String source, String sourceReference,
			UUID establishedBy, OffsetDateTime effectiveFrom, OffsetDateTime effectiveUntil) {
		jdbc.sql("""
				insert into current_service_entitlement (
				    account_id, package_code, source, source_reference, established_by,
				    effective_from, effective_until, policy_version
				) values (
				    :accountId, :packageCode, :source, :sourceReference, :establishedBy,
				    :effectiveFrom, :effectiveUntil, 'service-entitlement-v1'
				)
				""").param("accountId", accountId).param("packageCode", packageCode).param("source", source)
				.param("sourceReference", sourceReference).param("establishedBy", establishedBy)
				.param("effectiveFrom", effectiveFrom).param("effectiveUntil", effectiveUntil).update();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return role(id, "ROLE_USER");
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor role(UUID id, String authority) {
		return jwt().jwt(token -> token.subject(id.toString()))
				.authorities(new SimpleGrantedAuthority(authority));
	}
}
