package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.authentication.JwtTokenService;

import jakarta.persistence.EntityManager;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAccountIntegrationTests extends IdentityTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired JwtTokenService tokens;
	@Autowired EntityManager entityManager;

	@Test
	void adminSearchDetailSuspendRestoreAndConcurrencyUseRealPersistence() throws Exception {
		UUID adminId = insertAccount("admin-mb365@example.com", RoleCode.ADMIN);
		UUID userId = insertAccount("user-mb365@example.com", RoleCode.USER);
		UUID specialistId = insertAccount("specialist-mb365@example.com", RoleCode.SPECIALIST);
		insertRefreshSession(userId);
		String adminToken = token(adminId, RoleCode.ADMIN);
		UUID correlationId = UUID.randomUUID();

		mvc.perform(get("/api/v1/admin/accounts")
				.header("Authorization", "Bearer " + adminToken)
				.param("status", "ACTIVE").param("role", "USER")
				.param("email", "USER-MB365@example.com").param("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].accountId").value(userId.toString()))
				.andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());

		mvc.perform(get("/api/v1/admin/accounts/{accountId}", userId)
				.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""));

		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", userId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"0\"").header("X-Correlation-Id", correlationId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISABLED\",\"reasonCode\":\"SAFETY_CONCERN\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.status").value("DISABLED"));

		entityManager.flush();
		assertThat(count("security_audit_event", "account_id", userId)).isEqualTo(1);
		assertThat(count("outbox_event", "aggregate_id", userId)).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from refresh_session where account_id = :id and revoked_at is not null")
				.param("id", userId).query(Long.class).single()).isEqualTo(1);

		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", userId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"1\"").header("X-Correlation-Id", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISABLED\",\"reasonCode\":\"SAFETY_CONCERN\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
		assertThat(count("security_audit_event", "account_id", userId)).isEqualTo(1);
		assertThat(count("outbox_event", "aggregate_id", userId)).isEqualTo(1);

		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", userId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"0\"").header("X-Correlation-Id", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\",\"reasonCode\":\"REVIEW_COMPLETED\"}"))
				.andExpect(status().isPreconditionFailed());

		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", userId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "bad").header("X-Correlation-Id", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\",\"reasonCode\":\"REVIEW_COMPLETED\"}"))
				.andExpect(status().isBadRequest());

		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", userId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"1\"").header("X-Correlation-Id", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\",\"reasonCode\":\"REVIEW_COMPLETED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		mvc.perform(get("/api/v1/admin/accounts").header("Authorization", "Bearer " + token(userId, RoleCode.USER)))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", adminId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"0\"").header("X-Correlation-Id", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISABLED\",\"reasonCode\":\"POLICY_VIOLATION\"}"))
				.andExpect(status().isForbidden());

		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", specialistId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"0\"").header("X-Correlation-Id", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISABLED\",\"reasonCode\":\"ACCOUNT_REVIEW_REQUIRED\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("SPECIALIST"));
	}

	private UUID insertAccount(String email, RoleCode role) {
		return jdbc.sql("""
				insert into account (email, password_hash, role_code, status, email_verified_at)
				values (:email, 'bcrypt-test-hash', :role, 'ACTIVE', now()) returning id
				""").param("email", email).param("role", role.name()).query(UUID.class).single();
	}

	private void insertRefreshSession(UUID accountId) {
		jdbc.sql("""
				insert into refresh_session (family_id, account_id, token_hash, expires_at)
				values (:familyId, :accountId, :tokenHash, now() + interval '1 day')
				""").param("familyId", UUID.randomUUID()).param("accountId", accountId)
				.param("tokenHash", "a".repeat(64)).update();
	}

	private String token(UUID accountId, RoleCode role) {
		return tokens.issue(accountId, role, Instant.now()).value();
	}

	private long count(String table, String column, UUID id) {
		return jdbc.sql("select count(*) from " + table + " where " + column + " = :id")
				.param("id", id).query(Long.class).single();
	}
}
