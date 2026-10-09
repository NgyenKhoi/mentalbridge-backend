package com.mentalbridge.identity.productjourney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.authentication.JwtTokenService;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductJourneyEndpointIntegrationTests extends IdentityTestProperties {

	private static final Instant FROM = Instant.parse("2002-04-05T00:00:00Z");
	private static final Instant TO = Instant.parse("2002-04-06T00:00:00Z");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired JwtTokenService tokens;
	@MockitoBean ProductJourneySourceClient sources;

	@Test
	void adminReceivesOnlyUserEventsAndUnavailableDependenciesRemainNull() throws Exception {
		insertAccount("journey-user@example.test", RoleCode.USER, FROM, FROM.plusSeconds(60));
		insertAccount("journey-prior-user@example.test", RoleCode.USER, FROM.minusSeconds(60), FROM.plusSeconds(120));
		insertAccount("journey-specialist@example.test", RoleCode.SPECIALIST, FROM, FROM.plusSeconds(60));
		insertAccount("journey-admin@example.test", RoleCode.ADMIN, FROM, FROM.plusSeconds(60));
		insertAccount("journey-upper-bound@example.test", RoleCode.USER, TO, TO);
		when(sources.care(any(), any(), any())).thenThrow(new IllegalStateException("care unavailable"));
		when(sources.consultation(any(), any(), any()))
				.thenThrow(new IllegalStateException("consultation unavailable"));

		var result = mvc.perform(get("/api/v1/admin/product-journey-metrics")
				.header("Authorization", "Bearer " + token(RoleCode.ADMIN))
				.param("from", FROM.toString()).param("to", TO.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.window.from").value(FROM.toString()))
				.andExpect(jsonPath("$.window.to").value(TO.toString()))
				.andExpect(jsonPath("$.sources[0].sourceVersion").value("identity-account-projection-v2"))
				.andExpect(jsonPath("$.stages[0].stage").value("USER_ACCOUNTS_REGISTERED"))
				.andExpect(jsonPath("$.stages[0].count").value(1))
				.andExpect(jsonPath("$.stages[1].stage").value("USER_ACCOUNTS_ACTIVATED"))
				.andExpect(jsonPath("$.stages[1].count").value(2))
				.andExpect(jsonPath("$.sources[1].status").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.sources[2].status").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.stages[2].count").value(nullValue()))
				.andExpect(jsonPath("$.stages[4].unavailableReason")
						.value("AUTHORITATIVE_USAGE_FACT_UNAVAILABLE"))
				.andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain(
				"PHQ", "GAD", "assessmentAnswers", "journal", "emotion", "chat", "privateNote");
	}

	@Test
	void finalAggregateEndpointAllowsOnlyAdmin() throws Exception {
		for (var role : new RoleCode[] { RoleCode.USER, RoleCode.SPECIALIST }) {
			mvc.perform(get("/api/v1/admin/product-journey-metrics")
					.header("Authorization", "Bearer " + token(role))
					.param("from", FROM.toString()).param("to", TO.toString()))
					.andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/v1/admin/product-journey-metrics")
				.param("from", FROM.toString()).param("to", TO.toString()))
				.andExpect(status().isUnauthorized());
	}

	private void insertAccount(String email, RoleCode role, Instant createdAt, Instant verifiedAt) {
		jdbc.sql("""
				insert into account (email, password_hash, role_code, status, email_verified_at, created_at, updated_at)
				values (:email, 'test-hash', :role, 'ACTIVE', :verifiedAt, :createdAt, :verifiedAt)
				""").param("email", email).param("role", role.name())
				.param("verifiedAt", Timestamp.from(verifiedAt)).param("createdAt", Timestamp.from(createdAt)).update();
	}

	private String token(RoleCode role) {
		return tokens.issue(UUID.randomUUID(), role, Instant.now()).value();
	}
}
