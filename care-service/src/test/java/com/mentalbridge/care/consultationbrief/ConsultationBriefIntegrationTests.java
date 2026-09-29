package com.mentalbridge.care.consultationbrief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConsultationBriefIntegrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@MockitoBean AppointmentContextClient appointments;

	@Test
	void ownerApprovesExactSnapshotAndRevocationBlocksFutureSpecialistReads() throws Exception {
		var fixture = fixture(Instant.now().plusSeconds(3_600));

		mvc.perform(put("/api/v1/consultation-briefs/{id}/draft", fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON)
				.content(body(fixture.evaluationId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.screeningContext.length()").value(2))
				.andExpect(jsonPath("$.version").value(0));
		mvc.perform(post("/api/v1/consultation-briefs/{id}/approve", fixture.appointmentId())
				.with(user(fixture.userId())).header("If-Match", "\"0\""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.sharingStatus").value("ACTIVE"))
				.andExpect(jsonPath("$.approvedSnapshotId").isNotEmpty())
				.andExpect(jsonPath("$.version").value(1));
		mvc.perform(post("/api/v1/consultation-briefs/{id}/approve", fixture.appointmentId())
				.with(user(fixture.userId())).header("If-Match", "\"1\""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONSULTATION_BRIEF_DRAFT_REQUIRED"));
		mvc.perform(get("/api/v1/specialist/consultation-briefs/{id}", fixture.appointmentId())
				.with(specialist(fixture.specialistId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.currentSituation").value("Work pressure this week"))
				.andExpect(jsonPath("$.userGoals[0]").value("Discuss a manageable next step"))
				.andExpect(jsonPath("$.snapshotVersion").value(1))
				.andExpect(jsonPath("$.assessmentAnswers").doesNotExist())
				.andExpect(jsonPath("$.diagnosis").doesNotExist());
		mvc.perform(post("/api/v1/consultation-briefs/{id}/revoke", fixture.appointmentId())
				.with(user(fixture.userId())).header("If-Match", "\"1\""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.sharingStatus").value("REVOKED"))
				.andExpect(jsonPath("$.version").value(2));
		mvc.perform(get("/api/v1/specialist/consultation-briefs/{id}", fixture.appointmentId())
				.with(specialist(fixture.specialistId())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CONSULTATION_BRIEF_ACCESS_DENIED"));

		assertThat(auditCount(fixture.appointmentId(), "READ", "ALLOWED")).isOne();
		assertThat(auditCount(fixture.appointmentId(), "READ", "DENIED")).isOne();
	}

	@Test
	void accessWindowAndDeletionFailClosedWithoutLeakingDraftContent() throws Exception {
		var fixture = fixture(Instant.now().plusSeconds(172_800));
		createAndApprove(fixture);

		mvc.perform(get("/api/v1/specialist/consultation-briefs/{id}", fixture.appointmentId())
				.with(specialist(fixture.specialistId())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CONSULTATION_BRIEF_ACCESS_TOO_EARLY"));
		mvc.perform(delete("/api/v1/consultation-briefs/{id}", fixture.appointmentId())
				.with(user(fixture.userId())).header("If-Match", "\"1\""))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/consultation-briefs/{id}", fixture.appointmentId())
				.with(user(fixture.userId())))
				.andExpect(status().isNotFound());

		var deleted = jdbc.sql("""
				select b.current_situation is null and b.support_evaluation_id is null and b.user_goals is null
				 and s.current_situation is null and s.support_evaluation_id is null
				 and s.screening_context is null and s.user_goals is null and s.deleted_at is not null
				from consultation_brief b
				join consultation_brief_snapshot s on s.brief_id=b.id
				where b.appointment_id=:id
				""").param("id", fixture.appointmentId()).query(Boolean.class).single();
		assertThat(deleted).isTrue();
	}

	@Test
	void expiredWindowAndDeletedScreeningSourceFailClosed() throws Exception {
		var expired = fixture(Instant.now().plusSeconds(3_600));
		createAndApprove(expired);
		jdbc.sql("""
				update consultation_brief_grant
				set access_start_at=now() - interval '2 hours', access_end_at=now() - interval '1 hour'
				where appointment_id=:appointmentId
				""").param("appointmentId", expired.appointmentId()).update();
		mvc.perform(get("/api/v1/specialist/consultation-briefs/{id}", expired.appointmentId())
				.with(specialist(expired.specialistId())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CONSULTATION_BRIEF_ACCESS_EXPIRED"));

		var deletedSource = fixture(Instant.now().plusSeconds(3_600));
		createAndApprove(deletedSource);
		jdbc.sql("delete from support_evaluation_v2_domain where support_evaluation_id=:evaluationId")
				.param("evaluationId", deletedSource.evaluationId()).update();
		mvc.perform(get("/api/v1/specialist/consultation-briefs/{id}", deletedSource.appointmentId())
				.with(specialist(deletedSource.specialistId())))
				.andExpect(status().isGone())
				.andExpect(jsonPath("$.code").value("CONSULTATION_BRIEF_UNAVAILABLE"));
		assertThat(auditCount(deletedSource.appointmentId(), "READ", "DENIED")).isOne();
	}

	@Test
	void wrongActorAndStaleVersionCannotChangeOrReadTheBrief() throws Exception {
		var fixture = fixture(Instant.now().plusSeconds(3_600));
		mvc.perform(put("/api/v1/consultation-briefs/{id}/draft", fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON)
				.content(body(fixture.evaluationId()))).andExpect(status().isOk());

		mvc.perform(post("/api/v1/consultation-briefs/{id}/approve", fixture.appointmentId())
				.with(user(fixture.userId())).header("If-Match", "\"9\""))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("CONSULTATION_BRIEF_VERSION_MISMATCH"));
		mvc.perform(get("/api/v1/specialist/consultation-briefs/{id}", fixture.appointmentId())
				.with(specialist(UUID.randomUUID())))
				.andExpect(status().isNotFound());
		assertThat(auditCount(fixture.appointmentId(), "READ", "DENIED")).isOne();
	}

	@Test
	void rescheduledAppointmentInvalidatesThePreviouslyApprovedGrant() throws Exception {
		var fixture = fixture(Instant.now().plusSeconds(3_600));
		createAndApprove(fixture);
		var rescheduledStart = fixture.startAt().plusSeconds(3_600);
		doReturn(new AppointmentContext(fixture.appointmentId(), fixture.userId(), fixture.specialistId(),
				"CONFIRMED", rescheduledStart, rescheduledStart.plusSeconds(3_600), 2))
				.when(appointments).get(any(), anyString(), any());

		mvc.perform(get("/api/v1/specialist/consultation-briefs/{id}", fixture.appointmentId())
				.with(specialist(fixture.specialistId())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CONSULTATION_BRIEF_APPOINTMENT_CHANGED"));
		assertThat(auditCount(fixture.appointmentId(), "READ", "DENIED")).isOne();
	}

	@Test
	void missingVersionAndApprovalAfterAppointmentStartAreRejectedByTheDocumentedContract() throws Exception {
		var fixture = fixture(Instant.now().plusSeconds(3_600));
		mvc.perform(put("/api/v1/consultation-briefs/{id}/draft", fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON)
				.content(body(fixture.evaluationId()))).andExpect(status().isOk());
		mvc.perform(post("/api/v1/consultation-briefs/{id}/approve", fixture.appointmentId())
				.with(user(fixture.userId())))
				.andExpect(status().isPreconditionRequired())
				.andExpect(jsonPath("$.code").value("CONSULTATION_BRIEF_VERSION_REQUIRED"));

		var startedAt = Instant.now().minusSeconds(60);
		doReturn(new AppointmentContext(fixture.appointmentId(), fixture.userId(), fixture.specialistId(),
				"CONFIRMED", startedAt, startedAt.plusSeconds(3_600), 2))
				.when(appointments).get(any(), anyString(), any());
		mvc.perform(post("/api/v1/consultation-briefs/{id}/approve", fixture.appointmentId())
				.with(user(fixture.userId())).header("If-Match", "\"0\""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONSULTATION_BRIEF_APPOINTMENT_ALREADY_STARTED"));
	}

	@Test
	void concurrentReadAndRevokeSerializeAndAllLaterReadsAreDenied() throws Exception {
		var fixture = fixture(Instant.now().plusSeconds(3_600));
		createAndApprove(fixture);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var read = executor.submit(() -> {
				start.await();
				return mvc.perform(get("/api/v1/specialist/consultation-briefs/{id}", fixture.appointmentId())
						.with(specialist(fixture.specialistId()))).andReturn().getResponse().getStatus();
			});
			var revoke = executor.submit(() -> {
				start.await();
				return mvc.perform(post("/api/v1/consultation-briefs/{id}/revoke", fixture.appointmentId())
						.with(user(fixture.userId())).header("If-Match", "\"1\""))
						.andReturn().getResponse().getStatus();
			});
			start.countDown();
			assertThat(revoke.get(10, TimeUnit.SECONDS)).isEqualTo(200);
			assertThat(read.get(10, TimeUnit.SECONDS)).isIn(200, 403);
		}
		finally {
			executor.shutdownNow();
		}

		mvc.perform(get("/api/v1/specialist/consultation-briefs/{id}", fixture.appointmentId())
				.with(specialist(fixture.specialistId())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CONSULTATION_BRIEF_ACCESS_DENIED"));
		assertThat(auditCount(fixture.appointmentId(), "READ", "ALLOWED")).isLessThanOrEqualTo(1);
		assertThat(auditCount(fixture.appointmentId(), "READ", "DENIED")).isGreaterThanOrEqualTo(1);
	}

	private void createAndApprove(Fixture fixture) throws Exception {
		mvc.perform(put("/api/v1/consultation-briefs/{id}/draft", fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON)
				.content(body(fixture.evaluationId()))).andExpect(status().isOk());
		mvc.perform(post("/api/v1/consultation-briefs/{id}/approve", fixture.appointmentId())
				.with(user(fixture.userId())).header("If-Match", "\"0\""))
				.andExpect(status().isOk());
	}

	private Fixture fixture(Instant startAt) {
		var userId = UUID.randomUUID();
		var specialistId = UUID.randomUUID();
		var appointmentId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id,display_name) values (:id,'Consultation brief user')")
				.param("id", userId).update();
		var phq9 = assessment(userId, PHQ9_DEFINITION);
		var gad7 = assessment(userId, GAD7_DEFINITION);
		var evaluationId = UUID.randomUUID();
		jdbc.sql("""
				insert into support_evaluation_v2
				 (id,user_id,phq9_assessment_id,gad7_assessment_id,policy_version,evaluated_at)
				values (:id,:userId,:phq9,:gad7,'mb-support-routing-capstone-v2',now())
				""").param("id", evaluationId).param("userId", userId).param("phq9", phq9).param("gad7", gad7).update();
		domain(evaluationId, phq9, PHQ9_DEFINITION, (short) 1, "PHQ9", "DEPRESSIVE_SYMPTOMS",
				"phq9-vi-vn-capstone-v2", "MILD", "PHQ9_LEVEL_MILD");
		domain(evaluationId, gad7, GAD7_DEFINITION, (short) 2, "GAD7", "ANXIETY_SYMPTOMS",
				"gad7-vi-vn-adult-v1", "MODERATE", "GAD7_LEVEL_MODERATE");
		doAnswer(invocation -> {
			var actorToken = invocation.getArgument(1, String.class);
			var actorUser = actorToken.contains("user");
			var actorSpecialist = actorToken.contains("specialist");
			if (!actorUser && !actorSpecialist) throw new com.mentalbridge.care.shared.ApiException(
					org.springframework.http.HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "The appointment was not found");
			return new AppointmentContext(appointmentId, userId, specialistId, "CONFIRMED", startAt,
					startAt.plusSeconds(3_600), 1);
		}).when(appointments).get(any(), anyString(), any());
		return new Fixture(appointmentId, userId, specialistId, evaluationId, startAt);
	}

	private UUID assessment(UUID userId, UUID definitionId) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into assessment_submission
				 (id,user_id,definition_id,idempotency_key,request_hash,privacy_policy_version,submitted_at)
				values (:id,:userId,:definitionId,:key,:hash,'privacy-capstone-v3',now())
				""").param("id", id).param("userId", userId).param("definitionId", definitionId)
				.param("key", "consultation-brief-" + UUID.randomUUID()).param("hash", "0".repeat(64)).update();
		return id;
	}

	private void domain(UUID evaluationId, UUID assessmentId, UUID definitionId, short ordinal,
			String instrument, String domain, String questionnaireVersion, String screeningLevel, String reason) {
		jdbc.sql("""
				insert into support_evaluation_v2_domain
				 (id,support_evaluation_id,ordinal,assessment_id,definition_id,instrument,domain,
				  questionnaire_version,scoring_version,screening_level,support_pathway,reason_code)
				values (:id,:evaluationId,:ordinal,:assessmentId,:definitionId,:instrument,:domain,
				 :questionnaireVersion,:scoringVersion,:screeningLevel,:pathway,:reason)
				""").param("id", UUID.randomUUID()).param("evaluationId", evaluationId).param("ordinal", ordinal)
				.param("assessmentId", assessmentId).param("definitionId", definitionId).param("instrument", instrument)
				.param("domain", domain).param("questionnaireVersion", questionnaireVersion)
				.param("scoringVersion", instrument.equals("PHQ9") ? "phq9-standard-bands-v1" : "gad7-standard-bands-v1")
				.param("screeningLevel", screeningLevel).param("pathway", screeningLevel.equals("MILD")
						? "SELF_GUIDED_SUPPORT" : "PROFESSIONAL_SUPPORT_RECOMMENDED")
				.param("reason", reason).update();
	}

	private String body(UUID evaluationId) {
		return """
				{"currentSituation":"Work pressure this week","supportEvaluationId":"%s",
				 "userGoals":["Discuss a manageable next step"]}
				""".formatted(evaluationId);
	}

	private long auditCount(UUID appointmentId, String action, String outcome) {
		return jdbc.sql("""
				select count(*) from consultation_brief_audit
				where appointment_id=:appointmentId and action=:action and outcome=:outcome
				""").param("appointmentId", appointmentId).param("action", action).param("outcome", outcome)
				.query(Long.class).single();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).tokenValue("synthetic-user-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor specialist(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).tokenValue("synthetic-specialist-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"));
	}

	private record Fixture(UUID appointmentId, UUID userId, UUID specialistId, UUID evaluationId,
			Instant startAt) { }
}
