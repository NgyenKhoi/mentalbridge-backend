package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.authentication.JwtTokenService;
import com.mentalbridge.identity.security.BCryptPasswordHasher;

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
	@Autowired BCryptPasswordHasher passwordHasher;
	@Autowired ObjectMapper objectMapper;

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

		entityManager.flush();
		assertThat(count("security_audit_event", "account_id", adminId)).isEqualTo(1);
		assertThat(count("outbox_event", "aggregate_id", adminId)).isEqualTo(0);
		assertThat(jdbc.sql("select outcome from security_audit_event where account_id = :id")
				.param("id", adminId).query(String.class).single()).isEqualTo("DENIED");
		assertThat(jdbc.sql("select status from account where id = :id")
				.param("id", adminId).query(String.class).single()).isEqualTo("ACTIVE");

		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", specialistId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"0\"").header("X-Correlation-Id", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISABLED\",\"reasonCode\":\"ACCOUNT_REVIEW_REQUIRED\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("SPECIALIST"));
	}

	@Test
	void stateChangeSucceedsWithoutCallerSuppliedCorrelationHeaderAndReusesGeneratedId() throws Exception {
		UUID adminId = insertAccount("admin-corr-test@example.com", RoleCode.ADMIN);
		UUID userId = insertAccount("user-corr-test@example.com", RoleCode.USER);
		String adminToken = token(adminId, RoleCode.ADMIN);

		var result = mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", userId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISABLED\",\"reasonCode\":\"SAFETY_CONCERN\"}"))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Correlation-Id"))
				.andExpect(jsonPath("$.status").value("DISABLED"))
				.andReturn();

		String generatedCorrelationHeader = result.getResponse().getHeader("X-Correlation-Id");
		assertThat(generatedCorrelationHeader).isNotBlank();
		UUID generatedCorrelationId = UUID.fromString(generatedCorrelationHeader);

		entityManager.flush();
		UUID auditCorrelationId = jdbc.sql("select correlation_id from security_audit_event where account_id = :id")
				.param("id", userId).query(UUID.class).single();
		UUID outboxCorrelationId = jdbc.sql("select correlation_id from outbox_event where aggregate_id = :id")
				.param("id", userId).query(UUID.class).single();

		assertThat(auditCorrelationId).isEqualTo(generatedCorrelationId);
		assertThat(outboxCorrelationId).isEqualTo(generatedCorrelationId);
	}

	@Test
	void dedicatedAdminMutationReturnsForbiddenLeavesAccountUnchangedWritesDeniedAuditAndZeroOutbox() throws Exception {
		UUID adminId = insertAccount("admin-protect-test@example.com", RoleCode.ADMIN);
		String adminToken = token(adminId, RoleCode.ADMIN);
		UUID correlationId = UUID.randomUUID();

		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", adminId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"0\"")
				.header("X-Correlation-Id", correlationId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISABLED\",\"reasonCode\":\"POLICY_VIOLATION\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.correlationId").value(correlationId.toString()))
				.andExpect(header().string("X-Correlation-Id", correlationId.toString()));

		entityManager.flush();

		assertThat(jdbc.sql("select status from account where id = :id").param("id", adminId).query(String.class).single())
				.isEqualTo("ACTIVE");
		assertThat(jdbc.sql("select version from account where id = :id").param("id", adminId).query(Long.class).single())
				.isEqualTo(0L);

		assertThat(count("security_audit_event", "account_id", adminId)).isEqualTo(1);
		assertThat(count("outbox_event", "aggregate_id", adminId)).isEqualTo(0);

		var auditRow = jdbc.sql("select action, outcome, reason_code, correlation_id from security_audit_event where account_id = :id")
				.param("id", adminId).query().singleRow();
		assertThat(auditRow.get("action")).isEqualTo("ACCOUNT_DISABLED");
		assertThat(auditRow.get("outcome")).isEqualTo("DENIED");
		assertThat(auditRow.get("reason_code")).isEqualTo("POLICY_VIOLATION");
		assertThat(auditRow.get("correlation_id")).isEqualTo(correlationId);
	}

	@Test
	void specialistLifecycleSuspendRestoreAndRepeatedStateIdempotent() throws Exception {
		UUID adminId = insertAccount("admin-spec-mb416@example.com", RoleCode.ADMIN);
		UUID specialistId = insertAccount("specialist-lifecycle-mb416@example.com", RoleCode.SPECIALIST);
		String adminToken = token(adminId, RoleCode.ADMIN);
		UUID suspendCorrelationId = UUID.randomUUID();

		// 1. Suspend SPECIALIST
		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", specialistId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"0\"")
				.header("X-Correlation-Id", suspendCorrelationId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISABLED\",\"reasonCode\":\"ACCOUNT_REVIEW_REQUIRED\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.status").value("DISABLED"))
				.andExpect(jsonPath("$.roles[0]").value("SPECIALIST"));

		entityManager.flush();
		assertThat(count("security_audit_event", "account_id", specialistId)).isEqualTo(1);
		assertThat(count("outbox_event", "aggregate_id", specialistId)).isEqualTo(1);

		var suspendOutbox = jdbc.sql("select payload from outbox_event where aggregate_id = :id and aggregate_version = 1")
				.param("id", specialistId).query(String.class).single();
		assertThat(suspendOutbox).contains("\"role\": \"SPECIALIST\"");
		assertThat(suspendOutbox).contains("\"status\": \"DISABLED\"");
		assertThat(suspendOutbox).contains("\"reasonCode\": \"ACCOUNT_REVIEW_REQUIRED\"");

		// 2. Repeated suspend (idempotent no-op)
		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", specialistId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"1\"")
				.header("X-Correlation-Id", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISABLED\",\"reasonCode\":\"ACCOUNT_REVIEW_REQUIRED\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.status").value("DISABLED"));

		entityManager.flush();
		assertThat(count("security_audit_event", "account_id", specialistId)).isEqualTo(1);
		assertThat(count("outbox_event", "aggregate_id", specialistId)).isEqualTo(1);

		// 3. Restore SPECIALIST
		UUID restoreCorrelationId = UUID.randomUUID();
		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", specialistId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"1\"")
				.header("X-Correlation-Id", restoreCorrelationId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\",\"reasonCode\":\"REVIEW_COMPLETED\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.roles[0]").value("SPECIALIST"));

		entityManager.flush();
		assertThat(count("security_audit_event", "account_id", specialistId)).isEqualTo(2);
		assertThat(count("outbox_event", "aggregate_id", specialistId)).isEqualTo(2);

		var restoreOutbox = jdbc.sql("select payload from outbox_event where aggregate_id = :id and aggregate_version = 2")
				.param("id", specialistId).query(String.class).single();
		assertThat(restoreOutbox).contains("\"role\": \"SPECIALIST\"");
		assertThat(restoreOutbox).contains("\"status\": \"ACTIVE\"");
		assertThat(restoreOutbox).contains("\"reasonCode\": \"REVIEW_COMPLETED\"");

		// 4. Repeated restore (idempotent no-op)
		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", specialistId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"2\"")
				.header("X-Correlation-Id", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\",\"reasonCode\":\"REVIEW_COMPLETED\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		entityManager.flush();
		assertThat(count("security_audit_event", "account_id", specialistId)).isEqualTo(2);
		assertThat(count("outbox_event", "aggregate_id", specialistId)).isEqualTo(2);
	}

	@Test
	void userSuspendRevokesSessionsRejectsAuthenticationAndRestoreReEnablesLogin() throws Exception {
		UUID adminId = insertAccount("admin-auth-mb416@example.com", RoleCode.ADMIN);
		String rawPassword = "ValidPassword123!";
		UUID userId = insertAccount("user-auth-mb416@example.com", RoleCode.USER, passwordHasher.hash(rawPassword));
		String adminToken = token(adminId, RoleCode.ADMIN);

		// 1. Initial login succeeds
		String loginBody = """
				{"email":"user-auth-mb416@example.com","password":"ValidPassword123!","deviceLabel":"Integration Test Device"}
				""";
		String loginResponse = mvc.perform(post("/api/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").exists())
				.andExpect(jsonPath("$.refreshToken").exists())
				.andReturn().getResponse().getContentAsString();

		String refreshToken = objectMapper.readTree(loginResponse).get("refreshToken").asText();

		// 2. Suspend USER (account version was incremented to 1 during successful login)
		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", userId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISABLED\",\"reasonCode\":\"SAFETY_CONCERN\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.status").value("DISABLED"));

		entityManager.flush();

		// Refresh session in DB must be revoked
		assertThat(jdbc.sql("select count(*) from refresh_session where account_id = :id and revoked_at is not null and revoke_reason = 'ACCOUNT_DISABLED'")
				.param("id", userId).query(Long.class).single()).isGreaterThanOrEqualTo(1L);

		// 3. Refresh with previous token is rejected with 401 INVALID_SESSION
		mvc.perform(post("/api/v1/auth/refresh")
				.header("Idempotency-Key", "idemp-refresh-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refreshToken + "\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_SESSION"));

		// 4. Login attempt is rejected with 401 INVALID_CREDENTIALS
		mvc.perform(post("/api/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

		// 5. Restore USER
		mvc.perform(put("/api/v1/admin/accounts/{accountId}/state", userId)
				.header("Authorization", "Bearer " + adminToken)
				.header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\",\"reasonCode\":\"REVIEW_COMPLETED\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"3\""))
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		entityManager.flush();

		// 6. Login succeeds again after restore
		mvc.perform(post("/api/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").exists())
				.andExpect(jsonPath("$.refreshToken").exists());
	}

	private UUID insertAccount(String email, RoleCode role) {
		return insertAccount(email, role, "bcrypt-test-hash");
	}

	private UUID insertAccount(String email, RoleCode role, String passwordHash) {
		return jdbc.sql("""
				insert into account (email, password_hash, role_code, status, email_verified_at)
				values (:email, :hash, :role, 'ACTIVE', now()) returning id
				""").param("email", email).param("hash", passwordHash).param("role", role.name()).query(UUID.class).single();
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
