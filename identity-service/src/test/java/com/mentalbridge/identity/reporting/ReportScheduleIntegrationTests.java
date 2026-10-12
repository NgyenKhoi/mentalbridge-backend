package com.mentalbridge.identity.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.authentication.JwtTokenService;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mentalbridge.identity.platform-reporting.enabled=false")
@AutoConfigureMockMvc
@Transactional
class ReportScheduleIntegrationTests extends IdentityTestProperties {
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired JwtTokenService tokens;
	@Autowired ObjectMapper mapper;
	@Autowired ReportScheduleService schedules;
	@Autowired PlatformReportProcessor processor;
	@Autowired PlatformReportService reports;
	@Autowired jakarta.persistence.EntityManager entities;

	@Test
	void lifecycleRejectsStaleWritesAndRetainsGeneratedReportAfterDeletion() throws Exception {
		UUID admin = account(RoleCode.ADMIN);
		String token = token(admin, RoleCode.ADMIN);
		var created = create(token);
		String id = created.get("scheduleId").asText();
		assertThat(created.get("nextRunAt").asText()).isNotBlank();
		mvc.perform(put("/api/v1/admin/platform-report-schedules/{id}?expectedVersion=0", id)
				.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(false)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"));
		assertThat(schedules.enqueueNext()).isFalse();
		mvc.perform(put("/api/v1/admin/platform-report-schedules/{id}?expectedVersion=0", id)
				.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(true)))
				.andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		mvc.perform(put("/api/v1/admin/platform-report-schedules/{id}?expectedVersion=1", id)
				.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(true)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		due(id);
		assertThat(schedules.enqueueNext()).isTrue();
		assertThat(schedules.enqueueNext()).isFalse();
		UUID report = jdbc.sql("select id from platform_report_job where schedule_id = :id").param("id", UUID.fromString(id)).query(UUID.class).single();
		assertThat(processor.processNext()).isTrue();
		String content = new String(reports.download(report).content(), java.nio.charset.StandardCharsets.UTF_8);
		assertThat(content).contains("platform-account-activity-report-v1").doesNotContain("email", "journal", "assessment", "chat");
		long version = jdbc.sql("select version from platform_report_schedule where id = :id").param("id", UUID.fromString(id)).query(Long.class).single();
		mvc.perform(delete("/api/v1/admin/platform-report-schedules/{id}", id).queryParam("expectedVersion", Long.toString(version))
				.header("Authorization", token)).andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/admin/platform-report-schedules").header("Authorization", token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		assertThat(reports.download(report).content()).isNotEmpty();
	}

	@Test
	void authorizationAndUnsupportedDeliveryFailClosed() throws Exception {
		String admin = token(account(RoleCode.ADMIN), RoleCode.ADMIN);
		String user = token(account(RoleCode.USER), RoleCode.USER);
		mvc.perform(get("/api/v1/admin/platform-report-schedules")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/admin/platform-report-schedules").header("Authorization", user)
				.contentType(MediaType.APPLICATION_JSON).content(body(true))).andExpect(status().isForbidden());
		for (String invalid : new String[] {body(true).replace("ADMIN_REPORT_HISTORY", "EMAIL"),
				body(true).replace("Asia/Ho_Chi_Minh", "Invalid/Zone"), body(true).replace("\"periodDays\":7", "\"periodDays\":367"),
				body(true).replace("08:00", "08:00:01"), body(true).replace("\"enabled\":true", "\"enabled\":null")}) {
			mvc.perform(post("/api/v1/admin/platform-report-schedules").header("Authorization", admin)
					.contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
		}
		mvc.perform(delete("/api/v1/admin/platform-report-schedules/{id}?expectedVersion=-1", UUID.randomUUID())
				.header("Authorization", admin)).andExpect(status().isBadRequest());
		mvc.perform(delete("/api/v1/admin/platform-report-schedules/{id}", UUID.randomUUID())
				.header("Authorization", admin)).andExpect(status().isBadRequest());
	}

	@Test
	void scheduleLimitIncludesPausedSchedulesAndDeletionReleasesCapacity() throws Exception {
		String admin = token(account(RoleCode.ADMIN), RoleCode.ADMIN);
		String id = create(admin).get("scheduleId").asText();
		jdbc.sql("""
				insert into platform_report_schedule
				(id,report_type,cadence,timezone,local_time,period_days,recipient_group,delivery_target,status,created_by,created_at,updated_at,next_run_at,version)
				select gen_random_uuid(),report_type,cadence,timezone,local_time,period_days,recipient_group,delivery_target,'PAUSED',created_by,created_at,updated_at,next_run_at,0
				from platform_report_schedule cross join generate_series(1,49) where id=:id
				""").param("id", UUID.fromString(id)).update();
		mvc.perform(post("/api/v1/admin/platform-report-schedules").header("Authorization", admin)
				.contentType(MediaType.APPLICATION_JSON).content(body(true))).andExpect(status().isConflict());
		mvc.perform(delete("/api/v1/admin/platform-report-schedules/{id}?expectedVersion=0", id)
				.header("Authorization", admin)).andExpect(status().isNoContent());
		create(admin);
	}

	@Test
	void revokedCreatorPausesScheduleWithoutGeneratingAnArtifact() throws Exception {
		UUID admin = account(RoleCode.ADMIN);
		String id = create(token(admin, RoleCode.ADMIN)).get("scheduleId").asText();
		due(id);
		jdbc.sql("update account set status='DISABLED' where id=:id").param("id", admin).update();
		assertThat(schedules.enqueueNext()).isTrue();
		entities.flush();
		assertThat(jdbc.sql("select last_failure_code from platform_report_schedule where id=:id").param("id", UUID.fromString(id)).query(String.class).single())
				.isEqualTo("ADMIN_ACCESS_UNAVAILABLE");
		assertThat(jdbc.sql("select count(*) from platform_report_job").query(Long.class).single()).isZero();
	}

	@Test
	void failedGenerationIsAuditedAndRetryCreatesOneNewVersionedJob() throws Exception {
		UUID admin = account(RoleCode.ADMIN);
		String id = create(token(admin, RoleCode.ADMIN)).get("scheduleId").asText();
		due(id);
		schedules.enqueueNext();
		entities.flush();
		UUID job = jdbc.sql("select id from platform_report_job where schedule_id=:id").param("id", UUID.fromString(id)).query(UUID.class).single();
		jdbc.sql("update platform_report_job set source_versions='{}'::jsonb where id=:id").param("id", job).update();
		entities.clear();
		processor.processNext();
		entities.flush();
		assertThat(jdbc.sql("select failure_code from platform_report_job where id=:id").param("id", job).query(String.class).single()).isEqualTo("SOURCE_VERSION_UNAVAILABLE");
		var retry = reports.retry(admin, job, "schedule-retry-0001");
		assertThat(reports.retry(admin, job, "schedule-retry-0001").reportId()).isEqualTo(retry.reportId());
		processor.processNext();
		assertThat(reports.download(retry.reportId()).content()).isNotEmpty();
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentSchedulerExecutionsCommitExactlyOneOccurrence() throws Exception {
		UUID admin = account(RoleCode.ADMIN);
		UUID schedule = null;
		try {
			schedule = UUID.fromString(create(token(admin, RoleCode.ADMIN)).get("scheduleId").asText());
			due(schedule.toString());
			try (var executor = Executors.newFixedThreadPool(2)) {
				var first = executor.submit(schedules::enqueueNext);
				var second = executor.submit(schedules::enqueueNext);
				first.get();
				second.get();
			}
			assertThat(jdbc.sql("select count(*) from platform_report_job where schedule_id=:id").param("id", schedule).query(Long.class).single()).isEqualTo(1);
		}
		finally {
			jdbc.sql("delete from platform_report_job where requested_by=:id").param("id", admin).update();
			if (schedule != null) jdbc.sql("delete from platform_report_schedule where id=:id").param("id", schedule).update();
			jdbc.sql("delete from account where id=:id").param("id", admin).update();
		}
	}

	private void due(String id) {
		if (entities.isJoinedToTransaction()) entities.flush();
		jdbc.sql("update platform_report_schedule set next_run_at=now()-interval '1 hour' where id=:id")
				.param("id", UUID.fromString(id)).update();
		entities.clear();
	}

	private com.fasterxml.jackson.databind.JsonNode create(String token) throws Exception {
		return mapper.readTree(mvc.perform(post("/api/v1/admin/platform-report-schedules").header("Authorization", token)
				.contentType(MediaType.APPLICATION_JSON).content(body(true))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}

	private String body(boolean enabled) {
		return """
				{"reportType":"ACCOUNT_ACTIVITY","cadence":"DAILY","timezone":"Asia/Ho_Chi_Minh","localTime":"08:00","periodDays":7,"recipientGroup":"ADMIN","deliveryTarget":"ADMIN_REPORT_HISTORY","enabled":%s}
				""".formatted(enabled);
	}

	private UUID account(RoleCode role) {
		return jdbc.sql("""
				insert into account (email,password_hash,role_code,status,email_verified_at,created_at,updated_at)
				values (:email,'test-hash',:role,'ACTIVE',now(),now(),now()) returning id
				""").param("email", UUID.randomUUID() + "@synthetic.invalid").param("role", role.name()).query(UUID.class).single();
	}

	private String token(UUID id, RoleCode role) { return "Bearer " + tokens.issue(id, role, Instant.now()).value(); }
}
