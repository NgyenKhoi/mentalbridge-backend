package com.mentalbridge.consultation.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mentalbridge.consultation.appointment-notifications.service-token=synthetic-appointment-test-token")
@AutoConfigureMockMvc
class AppointmentDecisionIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired AppointmentDecisionService decisions;
	@Autowired ChatSessionSettlementService settlements;
	@Autowired DataSource dataSource;

	@Test
	void assignedSpecialistAcceptsOnceAndCreditRemainsHeld() throws Exception {
		var fixture = requestedAppointment();

		mvc.perform(get("/api/v1/specialist/appointments").with(specialist(fixture.specialistId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].id").value(fixture.appointmentId().toString()));
		mvc.perform(post("/api/v1/specialist/appointments/{id}/accept", fixture.appointmentId())
				.with(specialist(fixture.specialistId())).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "accept-appointment-command-0001"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.decisionReason").value("SPECIALIST_ACCEPTED"))
				.andExpect(jsonPath("$.creditState").value("HELD"))
				.andExpect(jsonPath("$.version").value(1));
		mvc.perform(post("/api/v1/specialist/appointments/{id}/accept", fixture.appointmentId())
				.with(specialist(fixture.specialistId())).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "accept-appointment-command-0001"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));

		assertThat(decisionHistoryCount(fixture.appointmentId())).isOne();
		assertThat(creditState(fixture.creditId())).isEqualTo("HELD");
		assertThat(releaseCount(fixture.appointmentId())).isZero();
	}

	@Test
	void reminderTruthAndOutboxFollowTheConfirmedVersionUntilCancellation() throws Exception {
		var fixture = requestedAppointment();
		var endpoint = "/internal/v1/appointments/{id}/notification-eligibility";
		mvc.perform(get(endpoint, fixture.appointmentId()).param("appointmentVersion", "0"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get(endpoint, fixture.appointmentId()).param("appointmentVersion", "0")
				.header("X-MentalBridge-Service-Token", "synthetic-appointment-test-token"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(false));

		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0,
				"confirm-for-reminder-truth-test");
		mvc.perform(get(endpoint, fixture.appointmentId()).param("appointmentVersion", "1")
				.header("X-MentalBridge-Service-Token", "synthetic-appointment-test-token"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.eligible").value(true));
		mvc.perform(get(endpoint, fixture.appointmentId()).param("appointmentVersion", "0")
				.header("X-MentalBridge-Service-Token", "synthetic-appointment-test-token"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(false));

		mvc.perform(post("/api/v1/appointments/{id}/cancel", fixture.appointmentId())
				.with(user(fixture.userId())).header("If-Match", "\"1\"")
				.header("Idempotency-Key", "cancel-for-reminder-truth-test"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		mvc.perform(get(endpoint, fixture.appointmentId()).param("appointmentVersion", "1")
				.header("X-MentalBridge-Service-Token", "synthetic-appointment-test-token"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(false));

		var outbox = jdbc.sql("""
				select appointment_version, payload::text from appointment_outbox_event
				where appointment_id=:appointmentId order by appointment_version
				""").param("appointmentId", fixture.appointmentId())
				.query((row, ignored) -> List.of(row.getLong(1), row.getString(2))).list();
		assertThat(outbox).hasSize(3);
		assertThat(outbox.get(0).get(1).toString()).contains("REQUESTED");
		assertThat(outbox.get(1).get(1).toString()).contains("CONFIRMED");
		assertThat(outbox.get(2).get(1).toString()).contains("CANCELLED");
		assertThat(outbox.toString()).doesNotContainIgnoringCase("journal", "assessment", "brief", "chat content");
	}

	@Test
	void consultationBriefContextIsVisibleOnlyToTheOwnerAndAssignedSpecialist() throws Exception {
		var fixture = requestedAppointment();

		mvc.perform(get("/internal/v1/appointments/{id}/consultation-brief-context", fixture.appointmentId())
				.with(user(fixture.userId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.appointmentId").value(fixture.appointmentId().toString()))
				.andExpect(jsonPath("$.userAccountId").value(fixture.userId().toString()))
				.andExpect(jsonPath("$.specialistAccountId").value(fixture.specialistId().toString()))
				.andExpect(jsonPath("$.status").value("REQUESTED"))
				.andExpect(jsonPath("$.scheduledStartAt").exists())
				.andExpect(jsonPath("$.scheduledEndAt").exists())
				.andExpect(jsonPath("$.version").value(0))
				.andExpect(jsonPath("$.heldCreditId").doesNotExist());
		mvc.perform(get("/internal/v1/appointments/{id}/consultation-brief-context", fixture.appointmentId())
				.with(specialist(fixture.specialistId())))
				.andExpect(status().isOk());
		mvc.perform(get("/internal/v1/appointments/{id}/consultation-brief-context", fixture.appointmentId())
				.with(specialist(UUID.randomUUID())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
	}

	@Test
	void continuityRelationshipsIncludeOnlyApprovedSpecialistsBoundedAppointmentsAndAreAudited() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0,
				"accept-continuity-command-0001");

		mvc.perform(get("/internal/v1/specialist/client-relationships")
				.with(specialist(fixture.specialistId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.policyVersion").value("specialist-client-continuity-v1"))
				.andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].appointmentId").value(fixture.appointmentId().toString()))
				.andExpect(jsonPath("$.items[0].userAccountId").value(fixture.userId().toString()))
				.andExpect(jsonPath("$.items[0].status").value("CONFIRMED"));

		assertThat(jdbc.sql("""
				select count(*) from specialist_client_continuity_audit
				where specialist_account_id=:specialistId and outcome='ALLOWED'
				""").param("specialistId", fixture.specialistId()).query(Long.class).single()).isOne();

		jdbc.sql("""
				update specialist_profile
				set approval_status='SUSPENDED', decision_reason_code='ACCOUNT_REVIEW_REQUIRED'
				where account_id=:specialistId
				""")
				.param("specialistId", fixture.specialistId()).update();
		mvc.perform(get("/internal/v1/specialist/client-relationships")
				.with(specialist(fixture.specialistId())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SPECIALIST_CONTINUITY_ACCESS_DENIED"));
		mvc.perform(get("/internal/v1/appointments/{id}/consultation-brief-context", fixture.appointmentId())
				.with(specialist(fixture.specialistId())))
				.andExpect(status().isNotFound());
		assertThat(jdbc.sql("""
				select count(*) from specialist_client_continuity_audit
				where specialist_account_id=:specialistId and outcome='DENIED'
				  and reason_code='SPECIALIST_NOT_APPROVED'
				""").param("specialistId", fixture.specialistId()).query(Long.class).single()).isOne();
	}

	@Test
	void assignedSpecialistRejectsOnceAndReloadShowsReleasedCredit() throws Exception {
		var fixture = requestedAppointment();

		mvc.perform(post("/api/v1/specialist/appointments/{id}/reject", fixture.appointmentId())
				.with(specialist(fixture.specialistId())).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "reject-appointment-command-0001"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.decisionReason").value("SPECIALIST_REJECTED"))
				.andExpect(jsonPath("$.creditState").value("AVAILABLE"));
		mvc.perform(post("/api/v1/specialist/appointments/{id}/reject", fixture.appointmentId())
				.with(specialist(fixture.specialistId())).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "reject-appointment-command-0001"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.creditState").value("AVAILABLE"));
		mvc.perform(get("/api/v1/appointments").with(user(fixture.userId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("REJECTED"))
				.andExpect(jsonPath("$.items[0].creditState").value("AVAILABLE"));

		assertThat(decisionHistoryCount(fixture.appointmentId())).isOne();
		assertThat(releaseCount(fixture.appointmentId())).isOne();
	}

	@Test
	void concurrentDuplicateAcceptsReplayOneDecision() throws Exception {
		var fixture = requestedAppointment();

		var results = concurrentDecisions(fixture, true, "concurrent-accept-command-0001");

		assertThat(results).extracting(AppointmentResponse::status).containsOnly("CONFIRMED");
		assertThat(decisionHistoryCount(fixture.appointmentId())).isOne();
		assertThat(creditState(fixture.creditId())).isEqualTo("HELD");
		assertThat(releaseCount(fixture.appointmentId())).isZero();
	}

	@Test
	void concurrentDuplicateRejectionsReplayOneDecisionAndRelease() throws Exception {
		var fixture = requestedAppointment();

		var results = concurrentDecisions(fixture, false, "concurrent-reject-command-0001");

		assertThat(results).extracting(AppointmentResponse::status).containsOnly("REJECTED");
		assertThat(decisionHistoryCount(fixture.appointmentId())).isOne();
		assertThat(creditState(fixture.creditId())).isEqualTo("AVAILABLE");
		assertThat(releaseCount(fixture.appointmentId())).isOne();
	}

	@Test
	void wrongSpecialistAndStaleVersionFailWithoutChangingTheRequest() throws Exception {
		var fixture = requestedAppointment();

		mvc.perform(post("/api/v1/specialist/appointments/{id}/accept", fixture.appointmentId())
				.with(specialist(UUID.randomUUID())).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "wrong-specialist-command-001"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_ASSIGNED"));
		mvc.perform(post("/api/v1/specialist/appointments/{id}/accept", fixture.appointmentId())
				.with(specialist(fixture.specialistId())).header("If-Match", "\"7\"")
				.header("Idempotency-Key", "stale-appointment-command-001"))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_VERSION_MISMATCH"));

		assertThat(appointmentStatus(fixture.appointmentId())).isEqualTo("REQUESTED");
		assertThat(creditState(fixture.creditId())).isEqualTo("HELD");
		assertThat(decisionHistoryCount(fixture.appointmentId())).isZero();
	}

	@Test
	void schedulerExpiryIsIdempotentAndReleasesExactlyOnce() throws Exception {
		var fixture = requestedAppointment();
		makeDue(fixture.appointmentId());

		assertThat(decisions.dueRequestIds()).contains(fixture.appointmentId());
		assertThat(decisions.expire(fixture.appointmentId())).isTrue();
		assertThat(decisions.expire(fixture.appointmentId())).isFalse();
		mvc.perform(get("/api/v1/appointments").with(user(fixture.userId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("EXPIRED"))
				.andExpect(jsonPath("$.items[0].decisionReason").value("DECISION_DEADLINE_EXPIRED"))
				.andExpect(jsonPath("$.items[0].creditState").value("AVAILABLE"));

		assertThat(decisionHistoryCount(fixture.appointmentId())).isOne();
		assertThat(releaseCount(fixture.appointmentId())).isOne();
	}

	@Test
	void schedulerRecoversExpiryAfterTheHeldCreditPeriodEnded() throws Exception {
		var fixture = requestedAppointment();
		makeDue(fixture.appointmentId());
		jdbc.sql("""
				update service_credit_period set period_end=:periodEnd, updated_at=:periodEnd
				where id=(select period_id from service_credit where id=:creditId)
				""").param("periodEnd", Timestamp.from(Instant.now().minusSeconds(60)))
				.param("creditId", fixture.creditId()).update();

		assertThat(decisions.expire(fixture.appointmentId())).isTrue();
		assertThat(decisions.expire(fixture.appointmentId())).isFalse();

		assertThat(appointmentStatus(fixture.appointmentId())).isEqualTo("EXPIRED");
		assertThat(creditState(fixture.creditId())).isEqualTo("AVAILABLE");
		assertThat(decisionHistoryCount(fixture.appointmentId())).isOne();
		assertThat(releaseCount(fixture.appointmentId())).isOne();
	}

	@Test
	void appointmentChatUsesServerTimeForWaitingActiveAndEndedPermissions() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-chat-window-command-001");

		setSchedule(fixture.appointmentId(), Instant.now().plusSeconds(20 * 60));
		chatEligibility(fixture.userId(), "USER", fixture.appointmentId(), "SUBSCRIBE")
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("TOO_EARLY"))
				.andExpect(jsonPath("$.subscribeAllowed").value(false))
				.andExpect(jsonPath("$.sendAllowed").value(false));

		setSchedule(fixture.appointmentId(), Instant.now().plusSeconds(5 * 60));
		chatEligibility(fixture.specialistId(), "SPECIALIST", fixture.appointmentId(), "SUBSCRIBE")
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("WAITING"))
				.andExpect(jsonPath("$.subscribeAllowed").value(true))
				.andExpect(jsonPath("$.sendAllowed").value(false));

		setSchedule(fixture.appointmentId(), Instant.now().minusSeconds(60));
		chatEligibility(fixture.userId(), "USER", fixture.appointmentId(), "SEND")
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("ACTIVE"))
				.andExpect(jsonPath("$.sendAllowed").value(true));

		setSchedule(fixture.appointmentId(), Instant.now().minusSeconds(3_700));
		chatEligibility(fixture.userId(), "USER", fixture.appointmentId(), "HISTORY")
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("ENDED_PROCESSING"))
				.andExpect(jsonPath("$.historyAllowed").value(true))
				.andExpect(jsonPath("$.sendAllowed").value(false));
	}

	@Test
	void appointmentChatHidesWrongActorsAndMakesCancellationReadOnly() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-chat-cancel-command-01");
		setSchedule(fixture.appointmentId(), Instant.now().plusSeconds(5 * 60));

		chatEligibility(UUID.randomUUID(), "USER", fixture.appointmentId(), "HISTORY")
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("APPOINTMENT_CHAT_NOT_FOUND"));

		jdbc.sql("""
				update appointment
				set status='CANCELLED', cancelled_at=now(), cancellation_reason='USER_CANCELLED',
				    cancelled_by=:actorId, cancellation_credit_outcome='RELEASED'
				where id=:id
				""").param("actorId", fixture.userId()).param("id", fixture.appointmentId()).update();
		chatEligibility(fixture.userId(), "USER", fixture.appointmentId(), "SEND")
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("CANCELLED"))
				.andExpect(jsonPath("$.historyAllowed").value(true))
				.andExpect(jsonPath("$.sendAllowed").value(false));
	}

	@Test
	void appointmentChatMakesReplacedAppointmentHistoryReadOnly() throws Exception {
		var original = requestedAppointment();
		decisions.accept(original.specialistId(), original.appointmentId(), 0,
				"accept-chat-reschedule-command-01");
		setSchedule(original.appointmentId(), Instant.now().plusSeconds(5 * 60));
		var replacement = requestedAppointment();
		jdbc.sql("update appointment set replaces_appointment_id=:originalId where id=:replacementId")
				.param("originalId", original.appointmentId())
				.param("replacementId", replacement.appointmentId()).update();
		jdbc.sql("""
				update appointment
				set status='CANCELLED', cancelled_at=now(), cancellation_reason='USER_RESCHEDULED',
				    cancelled_by=:actorId, cancellation_credit_outcome='TRANSFERRED_TO_REPLACEMENT'
				where id=:id
				""").param("actorId", original.userId()).param("id", original.appointmentId()).update();

		chatEligibility(original.specialistId(), "SPECIALIST", original.appointmentId(), "SEND")
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("RESCHEDULED"))
				.andExpect(jsonPath("$.subscribeAllowed").value(false))
				.andExpect(jsonPath("$.historyAllowed").value(true))
				.andExpect(jsonPath("$.sendAllowed").value(false));
	}

	@Test
	void explicitCheckInIsParticipantBoundAndIdempotentWithoutChatContent() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-chat-evidence-command-01");
		setSchedule(fixture.appointmentId(), Instant.now().plusSeconds(5 * 60));
		var evidenceId = UUID.randomUUID();
		var occurredAt = Instant.now().toString();
		var body = """
				{"evidenceId":"%s","type":"CHECK_IN","occurredAt":"%s"}
				""".formatted(evidenceId, occurredAt);

		mvc.perform(evidencePost(fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true))
				.andExpect(jsonPath("$.duplicate").value(false));
		mvc.perform(evidencePost(fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true))
				.andExpect(jsonPath("$.duplicate").value(true));
		chatEligibility(fixture.userId(), "USER", fixture.appointmentId(), "CHECK_IN")
				.andExpect(status().isOk()).andExpect(jsonPath("$.checkInAllowed").value(true))
				.andExpect(jsonPath("$.participantCheckedIn").value(true));
		mvc.perform(evidencePost(fixture.appointmentId())
				.with(user(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isNotFound());
		mvc.perform(evidencePost(fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"evidenceId":"%s","type":"CHECK_IN","occurredAt":"%s","content":"private"}
						""".formatted(UUID.randomUUID(), occurredAt)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void chatEvidenceRejectsCallsThatDoNotComeFromTheRealtimeService() throws Exception {
		var fixture = requestedAppointment();
		var body = """
				{"evidenceId":"%s","type":"CHECK_IN","occurredAt":"%s"}
				""".formatted(UUID.randomUUID(), Instant.now());

		mvc.perform(post("/internal/v1/appointments/{id}/chat-evidence", fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/v1/appointments/{id}/chat-evidence", fixture.appointmentId())
				.header("X-MentalBridge-Service-Token", "wrong-service-token-with-at-least-32-characters")
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());
		assertThat(evidenceCount(fixture.appointmentId())).isZero();
	}

	@Test
	void lateEvidenceCannotRewriteAWindowAfterTheFiveMinuteGrace() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-late-evidence-command-001");
		var start = Instant.now().minusSeconds(66 * 60);
		setSchedule(fixture.appointmentId(), start);
		var occurredAt = start.plusSeconds(30 * 60);

		mvc.perform(evidencePost(fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"evidenceId":"%s","type":"ACCEPTED_MESSAGE","occurredAt":"%s","messageId":"%s"}
						""".formatted(UUID.randomUUID(), occurredAt, UUID.randomUUID())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(false))
				.andExpect(jsonPath("$.reasonCode").value("EVIDENCE_WINDOW_CLOSED"));
	}

	@Test
	void delayedEvidenceThatOccurredBeforeEndIsAcceptedDuringGrace() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-grace-evidence-command-001");
		var start = Instant.now().minusSeconds(64 * 60);
		setSchedule(fixture.appointmentId(), start);

		mvc.perform(evidencePost(fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"evidenceId":"%s","type":"ACCEPTED_MESSAGE","occurredAt":"%s","messageId":"%s"}
						""".formatted(UUID.randomUUID(), start.plusSeconds(3_590), UUID.randomUUID())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
	}

	@Test
	void evidenceThatOccurredAfterEndIsRejectedDuringGrace() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-post-end-evidence-command-01");
		var start = Instant.now().minusSeconds(61 * 60);
		setSchedule(fixture.appointmentId(), start);

		mvc.perform(evidencePost(fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"evidenceId":"%s","type":"ACCEPTED_MESSAGE","occurredAt":"%s","messageId":"%s"}
						""".formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(false))
				.andExpect(jsonPath("$.reasonCode").value("EVIDENCE_OCCURRED_OUTSIDE_WINDOW"));
	}

	@Test
	void duplicateAcceptedMessageMetadataIsIdempotent() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-message-replay-command-001");
		setSchedule(fixture.appointmentId(), Instant.now().minusSeconds(60));
		var body = """
				{"evidenceId":"%s","type":"ACCEPTED_MESSAGE","occurredAt":"%s","messageId":"%s"}
				""".formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

		mvc.perform(evidencePost(fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(false));
		mvc.perform(evidencePost(fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true))
				.andExpect(jsonPath("$.duplicate").value(true));
		assertThat(evidenceCount(fixture.appointmentId())).isOne();
	}

	@Test
	void subscribingWithoutCheckInOrActivityRemainsBothNoShow() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-subscribe-only-command-001");
		setSchedule(fixture.appointmentId(), Instant.now().minusSeconds(65 * 60));
		chatEligibility(fixture.userId(), "USER", fixture.appointmentId(), "SUBSCRIBE")
				.andExpect(status().isOk()).andExpect(jsonPath("$.historyAllowed").value(true));

		settlements.end(fixture.appointmentId());
		settlements.settle(fixture.appointmentId());

		assertThat(evidenceCount(fixture.appointmentId())).isZero();
		assertThat(sessionOutcome(fixture.appointmentId())).isEqualTo("BOTH_NO_SHOW");
	}

	@Test
	void sessionEndKeepsCreditHeldUntilGraceThenReleasesBothNoShowExactlyOnce() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-both-no-show-command-001");
		setSchedule(fixture.appointmentId(), Instant.now().minusSeconds(65 * 60));

		settlements.end(fixture.appointmentId());

		assertThat(appointmentStatus(fixture.appointmentId())).isEqualTo("SESSION_ENDED");
		assertThat(sessionOutcome(fixture.appointmentId())).isNull();
		assertThat(creditState(fixture.creditId())).isEqualTo("HELD");
		assertThat(releaseCount(fixture.appointmentId())).isZero();

		settlements.settle(fixture.appointmentId());
		settlements.settle(fixture.appointmentId());

		assertThat(appointmentStatus(fixture.appointmentId())).isEqualTo("SESSION_ENDED");
		assertThat(sessionOutcome(fixture.appointmentId())).isEqualTo("BOTH_NO_SHOW");
		assertThat(creditState(fixture.creditId())).isEqualTo("AVAILABLE");
		assertThat(releaseCount(fixture.appointmentId())).isOne();
		assertThat(earningCount(fixture.appointmentId())).isZero();
	}

	@Test
	void completeSessionConsumesCreditAndCreatesOneCompletionFact() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-completed-chat-command-001");
		var start = Instant.now().minusSeconds(66 * 60);
		setSchedule(fixture.appointmentId(), start);
		insertCompletionEvidence(fixture, start);

		settlements.end(fixture.appointmentId());
		settlements.settle(fixture.appointmentId());
		settlements.settle(fixture.appointmentId());

		assertThat(appointmentStatus(fixture.appointmentId())).isEqualTo("COMPLETED");
		assertThat(sessionOutcome(fixture.appointmentId())).isEqualTo("COMPLETED");
		assertThat(completionFactId(fixture.appointmentId())).isNotNull();
		assertThat(creditState(fixture.creditId())).isEqualTo("CONSUMED");
		assertThat(creditEventCount(fixture.appointmentId(), "CONSUMED")).isOne();
		assertThat(creditEventTypes(fixture.appointmentId())).containsExactlyInAnyOrder("HELD", "CONSUMED");
		var earning = jdbc.sql("""
				select specialist_account_id, consumed_credit_id, currency, credit_allocation_minor,
				 specialist_share_bps, specialist_amount_minor, platform_allocation_minor, status
				from specialist_earning where appointment_id=:appointmentId
				""").param("appointmentId", fixture.appointmentId()).query((row, ignored) -> List.of(
				row.getObject("specialist_account_id", UUID.class), row.getObject("consumed_credit_id", UUID.class),
				row.getString("currency"), row.getLong("credit_allocation_minor"), row.getInt("specialist_share_bps"),
				row.getLong("specialist_amount_minor"), row.getLong("platform_allocation_minor"), row.getString("status"))).list();
		assertThat(earning).containsExactly(List.of(fixture.specialistId(), fixture.creditId(), "VND",
				300_000L, 7_000, 210_000L, 90_000L, "PENDING_SETTLEMENT"));
	}

	@Test
	void userNoShowForfeitsTheCreditExactlyOnce() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-user-no-show-command-001");
		var start = Instant.now().minusSeconds(66 * 60);
		setSchedule(fixture.appointmentId(), start);
		insertEvidence(fixture.appointmentId(), fixture.specialistId(), "SPECIALIST", "CHECK_IN", null, null, start);
		for (int minute = 0; minute < 15; minute++) {
			var intervalStart = start.plusSeconds(minute * 60L);
			insertEvidence(fixture.appointmentId(), fixture.specialistId(), "SPECIALIST", "PRESENCE_INTERVAL",
					intervalStart, null, intervalStart.plusSeconds(60));
		}

		settlements.end(fixture.appointmentId());
		settlements.settle(fixture.appointmentId());
		settlements.settle(fixture.appointmentId());

		assertThat(sessionOutcome(fixture.appointmentId())).isEqualTo("USER_NO_SHOW");
		assertThat(creditState(fixture.creditId())).isEqualTo("FORFEITED");
		assertThat(creditEventCount(fixture.appointmentId(), "FORFEITED")).isOne();
		assertThat(earningCount(fixture.appointmentId())).isZero();
	}

	@Test
	void recoveredEvidenceIsEvaluatedNormallyDuringTheExtendedWindow() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-recovered-evidence-command-001");
		var start = Instant.now().minusSeconds(66 * 60);
		setSchedule(fixture.appointmentId(), start);
		settlements.end(fixture.appointmentId());
		markEvidenceFailure(fixture.appointmentId());

		settlements.settle(fixture.appointmentId());
		assertThat(sessionOutcome(fixture.appointmentId())).isNull();
		assertThat(creditState(fixture.creditId())).isEqualTo("HELD");
		chatEligibility(fixture.userId(), "USER", fixture.appointmentId(), "HISTORY")
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("EVIDENCE_REVIEW"))
				.andExpect(jsonPath("$.creditState").value("HELD"));

		mvc.perform(evidencePost(fixture.appointmentId())
				.with(user(fixture.userId())).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"evidenceId":"%s","type":"ACCEPTED_MESSAGE","occurredAt":"%s","messageId":"%s"}
						""".formatted(UUID.randomUUID(), start.plusSeconds(30), UUID.randomUUID())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
		settlements.settle(fixture.appointmentId());

		assertThat(sessionOutcome(fixture.appointmentId())).isEqualTo("INSUFFICIENT_EVIDENCE");
		assertThat(creditState(fixture.creditId())).isEqualTo("AVAILABLE");
		assertThat(evidenceFailureReason(fixture.appointmentId())).isNull();
	}

	@Test
	void unresolvedEvidenceFailureReleasesCreditAtTheFinalDeadline() throws Exception {
		var fixture = requestedAppointment();
		decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, "accept-evidence-timeout-command-001");
		setSchedule(fixture.appointmentId(), Instant.now().minusSeconds(96 * 60));
		settlements.end(fixture.appointmentId());
		markEvidenceFailure(fixture.appointmentId());

		settlements.settle(fixture.appointmentId());
		settlements.settle(fixture.appointmentId());

		assertThat(sessionOutcome(fixture.appointmentId())).isEqualTo("EVIDENCE_REVIEW");
		assertThat(creditState(fixture.creditId())).isEqualTo("AVAILABLE");
		assertThat(releaseCount(fixture.appointmentId())).isOne();
	}

	@Test
	void decisionVersusExpiryRaceEndsExpiredWithoutDoubleRelease() throws Exception {
		var fixture = requestedAppointment();
		makeDue(fixture.appointmentId());
		var ready = new CountDownLatch(2);
		var go = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var accept = executor.submit(() -> {
				ready.countDown();
				go.await();
				return mvc.perform(post("/api/v1/specialist/appointments/{id}/accept", fixture.appointmentId())
						.with(specialist(fixture.specialistId())).header("If-Match", "\"0\"")
						.header("Idempotency-Key", "race-appointment-command-00001"))
						.andReturn().getResponse().getStatus();
			});
			var expiry = executor.submit(() -> {
				ready.countDown();
				go.await();
				return decisions.expire(fixture.appointmentId());
			});
			ready.await();
			go.countDown();
			assertThat(accept.get()).isIn(409, 412);
			assertThat(expiry.get()).isTrue();
		}

		assertThat(appointmentStatus(fixture.appointmentId())).isEqualTo("EXPIRED");
		assertThat(releaseCount(fixture.appointmentId())).isOne();
		assertThat(decisionHistoryCount(fixture.appointmentId())).isOne();
	}

	private List<AppointmentResponse> concurrentDecisions(Fixture fixture, boolean accept, String idempotencyKey)
			throws Exception {
		try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
				select a.id from appointment a join service_credit c on c.id=a.service_credit_id
				where a.id=? for update of a, c
				""")) {
			connection.setAutoCommit(false);
			statement.setObject(1, fixture.appointmentId());
			statement.executeQuery();
			var ready = new CountDownLatch(2);
			var go = new CountDownLatch(1);
			try (var executor = Executors.newFixedThreadPool(2)) {
				var first = executor.submit(() -> decide(fixture, accept, idempotencyKey, ready, go));
				var second = executor.submit(() -> decide(fixture, accept, idempotencyKey, ready, go));
				ready.await();
				go.countDown();
				var bothWaiting = waitForDecisionLocks();
				connection.commit();
				assertThat(bothWaiting).isTrue();
				return List.of(first.get(), second.get());
			}
		}
	}

	private AppointmentResponse decide(Fixture fixture, boolean accept, String idempotencyKey,
			CountDownLatch ready, CountDownLatch go) throws Exception {
		ready.countDown();
		go.await();
		return accept
				? decisions.accept(fixture.specialistId(), fixture.appointmentId(), 0, idempotencyKey)
				: decisions.reject(fixture.specialistId(), fixture.appointmentId(), 0, idempotencyKey);
	}

	private boolean waitForDecisionLocks() throws InterruptedException {
		for (int attempt = 0; attempt < 500; attempt++) {
			var waiting = jdbc.sql("""
					select count(*) from pg_stat_activity
					where datname=current_database() and wait_event_type='Lock'
					  and query like '%for update of a, c%'
					""").query(Long.class).single();
			if (waiting >= 2) return true;
			Thread.sleep(10);
		}
		return false;
	}

	private Fixture requestedAppointment() throws Exception {
		var userId = paidUser();
		var specialistId = UUID.randomUUID();
		var slotId = chatSlot(specialistId, Instant.now().plusSeconds(86_400));
		var result = mvc.perform(post("/api/v1/appointments").with(user(userId))
				.header("Idempotency-Key", "request-appointment-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"slotId\":\"%s\",\"modality\":\"IN_APP_CHAT\"}".formatted(slotId)))
				.andExpect(status().isCreated()).andReturn();
		JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
		return new Fixture(UUID.fromString(body.get("id").asText()), userId, specialistId,
				UUID.fromString(body.get("heldCreditId").asText()));
	}

	private UUID paidUser() {
		var id = UUID.randomUUID();
		var now = Instant.now();
		jdbc.sql("""
				insert into current_service_entitlement (
				 account_id, package_code, source, source_reference, effective_from, effective_until, policy_version
				) values (:id, 'PLUS', 'PAID', :reference, :from, :until, 'service-entitlement-v1')
				""").param("id", id).param("reference", "paid-" + id)
				.param("from", Timestamp.from(now.minusSeconds(60)))
				.param("until", Timestamp.from(now.plusSeconds(2_592_000))).update();
		return id;
	}

	private UUID chatSlot(UUID specialistId, Instant start) {
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Specialist', 'Appointment decision profile', 5, 'Asia/Ho_Chi_Minh',
				 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", UUID.randomUUID()).update();
		var slotId = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (
				 id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at
				) values (:id, :specialistId, :startAt, :endAt, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :key, now(), now())
				""").param("id", slotId).param("specialistId", specialistId).param("startAt", Timestamp.from(start))
				.param("endAt", Timestamp.from(start.plusSeconds(3_600))).param("key", "slot-key-" + slotId).update();
		return slotId;
	}

	private void makeDue(UUID appointmentId) {
		var now = Instant.now();
		jdbc.sql("update appointment set requested_at=:requestedAt, decision_deadline_at=:deadline where id=:id")
				.param("requestedAt", Timestamp.from(now.minusSeconds(90_000)))
				.param("deadline", Timestamp.from(now.minusSeconds(3_600))).param("id", appointmentId).update();
	}

	private void setSchedule(UUID appointmentId, Instant start) {
		var deadline = start.minusSeconds(60);
		jdbc.sql("""
				update appointment
				set requested_at=:requestedAt, decision_deadline_at=:deadline,
				    scheduled_start_at=:startAt, scheduled_end_at=:endAt
				where id=:id
				""").param("requestedAt", Timestamp.from(deadline.minusSeconds(60)))
				.param("deadline", Timestamp.from(deadline))
				.param("startAt", Timestamp.from(start))
				.param("endAt", Timestamp.from(start.plusSeconds(3_600)))
				.param("id", appointmentId).update();
	}

	private void insertCompletionEvidence(Fixture fixture, Instant start) {
		insertEvidence(fixture.appointmentId(), fixture.userId(), "USER", "CHECK_IN", null, null, start);
		insertEvidence(fixture.appointmentId(), fixture.specialistId(), "SPECIALIST", "CHECK_IN", null, null, start);
		insertEvidence(fixture.appointmentId(), fixture.userId(), "USER", "ACCEPTED_MESSAGE", null,
				UUID.randomUUID(), start.plusSeconds(30));
		insertEvidence(fixture.appointmentId(), fixture.specialistId(), "SPECIALIST", "ACCEPTED_MESSAGE", null,
				UUID.randomUUID(), start.plusSeconds(45));
		for (int minute = 0; minute < 30; minute++) {
			var intervalStart = start.plusSeconds(minute * 60L);
			insertEvidence(fixture.appointmentId(), fixture.userId(), "USER", "PRESENCE_INTERVAL", intervalStart,
					null, intervalStart.plusSeconds(60));
			insertEvidence(fixture.appointmentId(), fixture.specialistId(), "SPECIALIST", "PRESENCE_INTERVAL",
					intervalStart, null, intervalStart.plusSeconds(60));
		}
	}

	private void insertEvidence(UUID appointmentId, UUID actorId, String role, String type,
			Instant intervalStart, UUID messageId, Instant occurredAt) {
		jdbc.sql("""
				insert into appointment_chat_evidence (
				 id, appointment_id, evidence_id, participant_account_id, participant_role,
				 evidence_type, interval_started_at, message_id, occurred_at, received_at
				) values (:id, :appointmentId, :evidenceId, :actorId, :role,
				 :type, :intervalStart, :messageId, :occurredAt, now())
				""").param("id", UUID.randomUUID()).param("appointmentId", appointmentId)
				.param("evidenceId", UUID.randomUUID()).param("actorId", actorId).param("role", role)
				.param("type", type).param("intervalStart", intervalStart == null ? null : Timestamp.from(intervalStart))
				.param("messageId", messageId).param("occurredAt", Timestamp.from(occurredAt)).update();
	}

	private void markEvidenceFailure(UUID appointmentId) {
		jdbc.sql("update appointment set evidence_failure_reason='TEST_EVIDENCE_FAILURE' where id=:id")
				.param("id", appointmentId).update();
	}

	private org.springframework.test.web.servlet.ResultActions chatEligibility(UUID actorId, String role,
			UUID appointmentId, String operation) throws Exception {
		return mvc.perform(get("/internal/v1/appointments/{id}/chat-eligibility", appointmentId)
				.param("operation", operation)
				.with(jwt().jwt(token -> token.subject(actorId.toString()).claim("roles", List.of(role)))
						.authorities(new SimpleGrantedAuthority("ROLE_" + role))));
	}

	private MockHttpServletRequestBuilder evidencePost(UUID appointmentId) {
		return post("/internal/v1/appointments/{id}/chat-evidence", appointmentId)
				.header("X-MentalBridge-Service-Token", EVIDENCE_SERVICE_TOKEN);
	}

	private String appointmentStatus(UUID appointmentId) {
		return jdbc.sql("select status from appointment where id=:id").param("id", appointmentId)
				.query(String.class).single();
	}

	private String creditState(UUID creditId) {
		return jdbc.sql("select state from service_credit where id=:id").param("id", creditId)
				.query(String.class).single();
	}

	private long decisionHistoryCount(UUID appointmentId) {
		return jdbc.sql("""
				select count(*) from appointment_status_history
				where appointment_id=:id and reason <> 'APPOINTMENT_REQUESTED'
				""")
				.param("id", appointmentId).query(Long.class).single();
	}

	private long releaseCount(UUID appointmentId) {
		return jdbc.sql("""
				select count(*) from service_credit_ledger
				where appointment_id=:id and event_type='RELEASED'
				""").param("id", appointmentId).query(Long.class).single();
	}

	private long creditEventCount(UUID appointmentId, String eventType) {
		return jdbc.sql("""
				select count(*) from service_credit_ledger
				where appointment_id=:id and event_type=:eventType
				""").param("id", appointmentId).param("eventType", eventType).query(Long.class).single();
	}

	private List<String> creditEventTypes(UUID appointmentId) {
		return jdbc.sql("select event_type from service_credit_ledger where appointment_id=:id")
				.param("id", appointmentId).query(String.class).list();
	}

	private long evidenceCount(UUID appointmentId) {
		return jdbc.sql("select count(*) from appointment_chat_evidence where appointment_id=:id")
				.param("id", appointmentId).query(Long.class).single();
	}

	private long earningCount(UUID appointmentId) {
		return jdbc.sql("select count(*) from specialist_earning where appointment_id=:id")
				.param("id", appointmentId).query(Long.class).single();
	}

	private String sessionOutcome(UUID appointmentId) {
		return jdbc.sql("select session_outcome from appointment where id=:id").param("id", appointmentId)
				.query(String.class).optional().orElse(null);
	}

	private UUID completionFactId(UUID appointmentId) {
		return jdbc.sql("select completion_fact_id from appointment where id=:id").param("id", appointmentId)
				.query(UUID.class).optional().orElse(null);
	}

	private String evidenceFailureReason(UUID appointmentId) {
		return jdbc.sql("select evidence_failure_reason from appointment where id=:id").param("id", appointmentId)
				.query(String.class).optional().orElse(null);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("USER")))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor specialist(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("SPECIALIST")))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"));
	}

	private record Fixture(UUID appointmentId, UUID userId, UUID specialistId, UUID creditId) {
	}
}
