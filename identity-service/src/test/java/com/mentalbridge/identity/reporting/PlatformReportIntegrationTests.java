package com.mentalbridge.identity.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.authentication.JwtTokenService;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlatformReportIntegrationTests extends IdentityTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired JwtTokenService tokens;
	@Autowired PlatformReportProcessor processor;
	@Autowired ObjectMapper objectMapper;

	@Test
	void adminRequestsProcessesBrowsesAndDownloadsMinimizedAggregateArtifact() throws Exception {
		UUID adminId = insertAccount("report-admin@example.com", RoleCode.ADMIN, LocalDate.now().minusDays(10));
		insertAccount("report-user@example.com", RoleCode.USER, LocalDate.now().minusDays(2));
		insertAccount("report-specialist@example.com", RoleCode.SPECIALIST, LocalDate.now().minusDays(1));
		String token = token(adminId, RoleCode.ADMIN);
		String key = "report-request-" + UUID.randomUUID();
		String body = """
				{"reportType":"ACCOUNT_ACTIVITY","periodStart":"%s","periodEnd":"%s"}
				""".formatted(LocalDate.now().minusDays(7), LocalDate.now());

		String accepted = mvc.perform(post("/api/v1/admin/platform-reports")
				.header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.sourceVersions.identityAccounts").value("identity-account-projection-v1"))
				.andReturn().getResponse().getContentAsString();
		UUID reportId = UUID.fromString(objectMapper.readTree(accepted).get("reportId").asText());

		mvc.perform(post("/api/v1/admin/platform-reports")
				.header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.reportId").value(reportId.toString()));

		assertThat(processor.processNext()).isTrue();

		mvc.perform(get("/api/v1/admin/platform-reports").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
				.andExpect(jsonPath("$.items[0].downloadable").value(true))
				.andExpect(jsonPath("$.items[0].contentSha256").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));

		var downloadResult = mvc.perform(get("/api/v1/admin/platform-reports/{reportId}/artifact", reportId)
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
						org.hamcrest.Matchers.containsString("attachment")))
				.andExpect(header().string("X-Content-SHA256", org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
				.andReturn();
		var downloaded = downloadResult.getResponse().getContentAsByteArray();
		assertThat(downloadResult.getResponse().getHeader("X-Content-SHA256"))
				.isEqualTo(PlatformReportService.hash(downloaded));

		String artifact = new String(downloaded, java.nio.charset.StandardCharsets.UTF_8);
		assertThat(artifact).contains("createdAccountCount", "reportId", "identity-account-projection-v1")
				.doesNotContain("report-user@example.com", "password", "journal", "assessment", "chat",
						"private", "providerPayload");

		mvc.perform(post("/api/v1/admin/platform-reports")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", "report-pagination-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isAccepted());

		String firstPage = mvc.perform(get("/api/v1/admin/platform-reports?limit=1")
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.nextCursor").isString())
				.andReturn().getResponse().getContentAsString();
		String cursor = objectMapper.readTree(firstPage).get("nextCursor").asText();

		mvc.perform(get("/api/v1/admin/platform-reports").queryParam("limit", "1").queryParam("cursor", cursor)
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].reportId").value(reportId.toString()));
	}

	@Test
	void authorizationValidationAndIncompleteDownloadFailClosed() throws Exception {
		UUID adminId = insertAccount("report-admin-protection@example.com", RoleCode.ADMIN, LocalDate.now().minusDays(2));
		UUID userId = insertAccount("report-user-protection@example.com", RoleCode.USER, LocalDate.now().minusDays(1));
		String body = """
				{"reportType":"ACCOUNT_ACTIVITY","periodStart":"%s","periodEnd":"%s"}
				""".formatted(LocalDate.now().minusDays(2), LocalDate.now());

		mvc.perform(get("/api/v1/admin/platform-reports")
				.header("Authorization", "Bearer " + token(userId, RoleCode.USER)))
				.andExpect(status().isForbidden());

		String accepted = mvc.perform(post("/api/v1/admin/platform-reports")
				.header("Authorization", "Bearer " + token(adminId, RoleCode.ADMIN))
				.header("Idempotency-Key", "incomplete-report-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		UUID reportId = UUID.fromString(objectMapper.readTree(accepted).get("reportId").asText());

		mvc.perform(get("/api/v1/admin/platform-reports/{reportId}/artifact", reportId)
				.header("Authorization", "Bearer " + token(adminId, RoleCode.ADMIN)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PLATFORM_REPORT_NOT_COMPLETED"));

		mvc.perform(post("/api/v1/admin/platform-reports")
				.header("Authorization", "Bearer " + token(adminId, RoleCode.ADMIN))
				.header("Idempotency-Key", "future-report-key-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reportType\":\"ACCOUNT_ACTIVITY\",\"periodStart\":\"2099-01-01\",\"periodEnd\":\"2099-01-02\"}"))
				.andExpect(status().isBadRequest());
	}

	private UUID insertAccount(String email, RoleCode role, LocalDate created) {
		return jdbc.sql("""
				insert into account (email, password_hash, role_code, status, email_verified_at, created_at, updated_at)
				values (:email, 'bcrypt-test-hash', :role, 'ACTIVE', now(), :createdAt, :createdAt) returning id
				""").param("email", email).param("role", role.name())
				.param("createdAt", java.sql.Timestamp.from(created.atStartOfDay().toInstant(java.time.ZoneOffset.UTC)))
				.query(UUID.class).single();
	}

	private String token(UUID accountId, RoleCode role) {
		return tokens.issue(accountId, role, Instant.now()).value();
	}

}
