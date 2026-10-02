package com.mentalbridge.consultation.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SessionSummaryFlowIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;

	@Test
	void onlyAssignedSpecialistCanPublishAfterEvidenceBackedCompletion() throws Exception {
		var fixture = appointment();
		mvc.perform(publish(fixture, fixture.specialistId(), "summary-before-complete", summaryBody()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SESSION_SUMMARY_REQUIRES_COMPLETED_APPOINTMENT"));
		complete(fixture.appointmentId());
		mvc.perform(publish(fixture, UUID.randomUUID(), "summary-wrong-specialist", summaryBody()))
				.andExpect(status().isNotFound());
		mvc.perform(publish(fixture, fixture.specialistId(), "summary-with-private", """
				{"topicsDiscussed":["Giấc ngủ"],"followUpSuggested":false,"agreedNextSteps":[],
				 "privateNotes":"must not be accepted"}
				""")).andExpect(status().isBadRequest());
	}

	@Test
	void publishIsIdempotentAndUserStateIsNotVisibleToSpecialist() throws Exception {
		var fixture = completedAppointment();
		for (int replay = 0; replay < 2; replay++) {
			mvc.perform(publish(fixture, fixture.specialistId(), "summary-idempotent-0001", summaryBody()))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.version").value(1))
					.andExpect(jsonPath("$.agreedNextSteps[0].state").isEmpty());
		}
		assertThat(jdbc.sql("select count(*) from session_summary where appointment_id=:id")
				.param("id", fixture.appointmentId()).query(Long.class).single()).isOne();
		mvc.perform(get("/api/v1/appointments/{id}/session-summaries", fixture.appointmentId())
				.with(user(fixture.userId()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].reuseConsent.approved").value(false))
				.andExpect(jsonPath("$.items[0].agreedNextSteps[0].state").value("PENDING"));
		mvc.perform(get("/api/v1/specialist/appointments/{id}/session-summaries", fixture.appointmentId())
				.with(specialist(fixture.specialistId()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].reuseConsent").isEmpty())
				.andExpect(jsonPath("$.items[0].agreedNextSteps[0].state").isEmpty());
	}

	@Test
	void idempotencyReplayCannotCrossTheAppointmentBoundary() throws Exception {
		var fixture = completedAppointment();
		var key = "summary-appointment-boundary-01";
		mvc.perform(publish(fixture, fixture.specialistId(), key, summaryBody()))
				.andExpect(status().isCreated());

		mvc.perform(post("/api/v1/specialist/appointments/{id}/session-summaries", UUID.randomUUID())
				.with(specialist(fixture.specialistId())).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(summaryBody()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SESSION_SUMMARY_IDEMPOTENCY_CONFLICT"));
	}

	@Test
	void amendmentCreatesAnExplicitImmutableVersion() throws Exception {
		var fixture = completedAppointment();
		var first = mvc.perform(publish(fixture, fixture.specialistId(), "summary-version-one-01", summaryBody()))
				.andExpect(status().isCreated()).andReturn();
		var firstId = json.readTree(first.getResponse().getContentAsByteArray()).get("id").asText();

		mvc.perform(publish(fixture, fixture.specialistId(), "summary-version-two-01", summaryBody()))
				.andExpect(status().isPreconditionRequired());
		mvc.perform(publish(fixture, fixture.specialistId(), "summary-version-two-02", amendedBody())
				.header("If-Match", "\"1\""))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.amendsSummaryId").value(firstId));
		mvc.perform(get("/api/v1/appointments/{id}/session-summaries", fixture.appointmentId())
				.with(user(fixture.userId()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(2))
				.andExpect(jsonPath("$.items[0].version").value(2))
				.andExpect(jsonPath("$.items[1].version").value(1))
				.andExpect(jsonPath("$.items[1].topicsDiscussed[0]").value("Giấc ngủ"));
	}

	@Test
	void userOwnsNextStepAndSeparateReusableSummaryConsent() throws Exception {
		var fixture = completedAppointment();
		var published = mvc.perform(publish(fixture, fixture.specialistId(), "summary-user-controls-1", summaryBody()))
				.andExpect(status().isCreated()).andReturn();
		var body = json.readTree(published.getResponse().getContentAsByteArray());
		var summaryId = body.get("id").asText();
		var stepId = body.get("agreedNextSteps").get(0).get("id").asText();

		mvc.perform(put("/api/v1/agreed-next-steps/{id}", stepId).with(user(fixture.userId()))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"COMPLETED\",\"hidden\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.agreedNextSteps[0].state").value("COMPLETED"))
				.andExpect(jsonPath("$.agreedNextSteps[0].hidden").value(true));
		mvc.perform(put("/api/v1/session-summaries/{id}/reuse-consent", summaryId).with(user(fixture.userId()))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"approved\":true}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.reuseConsent.approved").value(true));
		var now = Instant.now();
		moveAppointment(fixture.appointmentId(), now.minusSeconds(172_800));
		var targetAppointmentId = laterAppointment(fixture, now.plusSeconds(3_600), "CONFIRMED");
		mvc.perform(get("/internal/v1/appointments/{appointmentId}/reusable-session-summaries/{summaryId}",
				fixture.appointmentId(), summaryId).param("version", "1").with(specialist(fixture.specialistId())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SESSION_SUMMARY_REUSE_NOT_ELIGIBLE"));
		mvc.perform(get("/internal/v1/appointments/{appointmentId}/reusable-session-summaries/{summaryId}",
				targetAppointmentId, summaryId).param("version", "1").with(specialist(fixture.specialistId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
		mvc.perform(get("/internal/v1/appointments/{appointmentId}/reusable-session-summaries/{summaryId}",
				targetAppointmentId, summaryId).param("version", "1").with(user(fixture.userId())))
				.andExpect(status().isOk());
		mvc.perform(put("/api/v1/session-summaries/{id}/reuse-consent", summaryId).with(user(fixture.userId()))
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"approved\":false}"))
				.andExpect(status().isOk());
		mvc.perform(get("/internal/v1/appointments/{appointmentId}/reusable-session-summaries/{summaryId}",
				targetAppointmentId, summaryId).param("version", "1").with(specialist(fixture.specialistId())))
				.andExpect(status().isForbidden());
	}

	@Test
	void reusableSummaryRequiresAnActiveLaterAppointmentAndSpecialistAccessWindow() throws Exception {
		var fixture = completedAppointment();
		var published = mvc.perform(publish(fixture, fixture.specialistId(), "summary-reuse-boundary-1", summaryBody()))
				.andExpect(status().isCreated()).andReturn();
		var summaryId = json.readTree(published.getResponse().getContentAsByteArray()).get("id").asText();
		mvc.perform(put("/api/v1/session-summaries/{id}/reuse-consent", summaryId).with(user(fixture.userId()))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"approved\":true}"))
				.andExpect(status().isOk());

		var now = Instant.now();
		moveAppointment(fixture.appointmentId(), now.minusSeconds(345_600));
		var targetAppointmentId = laterAppointment(fixture, now.plusSeconds(172_800), "CONFIRMED");
		mvc.perform(get("/internal/v1/appointments/{appointmentId}/reusable-session-summaries/{summaryId}",
				targetAppointmentId, summaryId).param("version", "1").with(specialist(fixture.specialistId())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SESSION_SUMMARY_REUSE_NOT_ELIGIBLE"));

		setTarget(targetAppointmentId, now.plusSeconds(3_600), "REQUESTED");
		mvc.perform(get("/internal/v1/appointments/{appointmentId}/reusable-session-summaries/{summaryId}",
				targetAppointmentId, summaryId).param("version", "1").with(user(fixture.userId())))
				.andExpect(status().isForbidden());

		setTarget(targetAppointmentId, now.minusSeconds(172_800), "CONFIRMED");
		mvc.perform(get("/internal/v1/appointments/{appointmentId}/reusable-session-summaries/{summaryId}",
				targetAppointmentId, summaryId).param("version", "1").with(specialist(fixture.specialistId())))
				.andExpect(status().isForbidden());
	}

	@Test
	void concurrentFirstPublicationCreatesOnlyOneVersion() throws Exception {
		var fixture = completedAppointment();
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> concurrentPublish(fixture, "summary-concurrent-01", ready, start));
			var second = executor.submit(() -> concurrentPublish(fixture, "summary-concurrent-02", ready, start));
			ready.await();
			start.countDown();
			assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 428);
		}
		assertThat(jdbc.sql("select count(*) from session_summary where appointment_id=:id")
				.param("id", fixture.appointmentId()).query(Long.class).single()).isOne();
	}

	private int concurrentPublish(Fixture fixture, String key, CountDownLatch ready, CountDownLatch start)
			throws Exception {
		ready.countDown();
		start.await();
		return mvc.perform(publish(fixture, fixture.specialistId(), key, summaryBody()))
				.andReturn().getResponse().getStatus();
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publish(Fixture fixture,
			UUID specialistId, String key, String body) {
		return post("/api/v1/specialist/appointments/{id}/session-summaries", fixture.appointmentId())
				.with(specialist(specialistId)).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private Fixture completedAppointment() throws Exception {
		var fixture = appointment();
		complete(fixture.appointmentId());
		return fixture;
	}

	private Fixture appointment() throws Exception {
		var userId = UUID.randomUUID();
		var specialistId = UUID.randomUUID();
		var now = Instant.now();
		jdbc.sql("""
				insert into current_service_entitlement (
				 account_id, package_code, source, source_reference, effective_from, effective_until, policy_version
				) values (:id, 'PLUS', 'PAID', :reference, :from, :until, 'service-entitlement-v1')
				""").param("id", userId).param("reference", "paid-" + userId)
				.param("from", Timestamp.from(now.minusSeconds(60)))
				.param("until", Timestamp.from(now.plusSeconds(2_592_000))).update();
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Specialist', 'Summary test specialist', 5, 'Asia/Ho_Chi_Minh',
				 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", UUID.randomUUID()).update();
		var slotId = UUID.randomUUID();
		var start = now.plusSeconds(86_400);
		jdbc.sql("""
				insert into availability_slot (
				 id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at
				) values (:id, :specialist, :start, :end, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :key, now(), now())
				""").param("id", slotId).param("specialist", specialistId).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3_600))).param("key", "slot-" + slotId).update();
		var result = mvc.perform(post("/api/v1/appointments").with(user(userId))
				.header("Idempotency-Key", "appointment-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"slotId\":\"%s\",\"modality\":\"IN_APP_CHAT\"}".formatted(slotId)))
				.andExpect(status().isCreated()).andReturn();
		JsonNode response = json.readTree(result.getResponse().getContentAsByteArray());
		return new Fixture(UUID.fromString(response.get("id").asText()), userId, specialistId);
	}

	private void complete(UUID appointmentId) {
		jdbc.sql("""
				update appointment set status='COMPLETED', decided_at=now(), decision_reason='SPECIALIST_ACCEPTED',
				 session_outcome='COMPLETED', session_outcome_reason='EVIDENCE_REQUIREMENTS_MET',
				 session_policy_version='chat-session-completion-v1', session_ended_at=now(),
				 session_settled_at=now(), completion_fact_id=:fact, updated_at=now()
				where id=:id
				""").param("fact", UUID.randomUUID()).param("id", appointmentId).update();
	}

	private void moveAppointment(UUID appointmentId, Instant start) {
		jdbc.sql("""
				update appointment set scheduled_start_at=:start, scheduled_end_at=:end,
				 requested_at=:requestedAt, decision_deadline_at=:deadline where id=:id
				""")
				.param("start", Timestamp.from(start)).param("end", Timestamp.from(start.plusSeconds(3_600)))
				.param("requestedAt", Timestamp.from(start.minusSeconds(18_000)))
				.param("deadline", Timestamp.from(start.minusSeconds(7_200)))
				.param("id", appointmentId).update();
	}

	private UUID laterAppointment(Fixture source, Instant start, String status) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into appointment (
				 id,user_account_id,specialist_account_id,availability_slot_id,service_credit_id,
				 status,modality,scheduled_start_at,scheduled_end_at,display_timezone,requested_at,
				 decision_deadline_at,idempotency_key,created_at,updated_at,version,decided_at,decision_reason
				)
				select :id,user_account_id,specialist_account_id,availability_slot_id,service_credit_id,
				 :status,modality,:start,:end,display_timezone,:requestedAt,:deadline,:key,now(),now(),0,
				 case when :status='REQUESTED' then null else now() end,
				 case when :status='REQUESTED' then null else 'SPECIALIST_ACCEPTED' end
				from appointment where id=:sourceId
				""").param("id", id).param("status", status).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3_600)))
				.param("requestedAt", Timestamp.from(start.minusSeconds(18_000)))
				.param("deadline", Timestamp.from(start.minusSeconds(7_200)))
				.param("key", "target-" + id).param("sourceId", source.appointmentId()).update();
		return id;
	}

	private void setTarget(UUID appointmentId, Instant start, String status) {
		jdbc.sql("""
				update appointment set status=:status,scheduled_start_at=:start,scheduled_end_at=:end,
				 requested_at=:requestedAt,decision_deadline_at=:deadline,
				 decided_at=case when :status='REQUESTED' then null else now() end,
				 decision_reason=case when :status='REQUESTED' then null else 'SPECIALIST_ACCEPTED' end
				where id=:id
				""").param("status", status).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3_600)))
				.param("requestedAt", Timestamp.from(start.minusSeconds(18_000)))
				.param("deadline", Timestamp.from(start.minusSeconds(7_200)))
				.param("id", appointmentId).update();
	}

	private String summaryBody() {
		return """
				{"topicsDiscussed":["Giấc ngủ"],"progressSummary":"Đã cùng nhìn lại nhịp ngủ gần đây.",
				 "specialistNoteForUser":"Bạn có thể bắt đầu từ một bước nhỏ.","followUpSuggested":true,
				 "agreedNextSteps":[{"type":"JOURNAL","title":"Viết nhật ký 3 ngày","details":"Ghi lại giờ ngủ."}]}
				""";
	}

	private String amendedBody() {
		return """
				{"topicsDiscussed":["Giấc ngủ","Thói quen buổi tối"],"progressSummary":"Bổ sung nội dung đã thống nhất.",
				 "specialistNoteForUser":null,"followUpSuggested":false,"agreedNextSteps":[]}
				""";
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("USER")))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor specialist(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("SPECIALIST")))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"));
	}

	private record Fixture(UUID appointmentId, UUID userId, UUID specialistId) { }
}
