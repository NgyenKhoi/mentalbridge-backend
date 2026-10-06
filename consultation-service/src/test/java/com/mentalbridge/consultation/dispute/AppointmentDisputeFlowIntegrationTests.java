package com.mentalbridge.consultation.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.shared.ApiException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AppointmentDisputeFlowIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired AppointmentSettlementGate settlementGate;
	@Autowired AppointmentDisputeService disputeService;

	@Test
	void participantReplayCreatesOneDisputeAndAdminReleaseCreatesOneExplicitAdjustment() throws Exception {
		var fixture = completedAppointment(Instant.now().minusSeconds(600));
		var body = """
				{"reasonCode":"OUTCOME_INCORRECT","evidenceType":"CONNECTION_INCIDENT",
				 "evidenceOccurredAt":"%s"}
				""".formatted(fixture.sessionStart().plusSeconds(900));
		String disputeId = null;
		for (int attempt = 0; attempt < 2; attempt++) {
			var result = mvc.perform(post("/api/v1/appointments/{id}/dispute", fixture.appointmentId())
					.with(actor(fixture.userId(), "USER")).header("Idempotency-Key", "open-dispute-command-0001")
					.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"))
					.andExpect(jsonPath("$.settlementGated").value(true))
					.andExpect(jsonPath("$.reasonCode").value("OUTCOME_INCORRECT")).andReturn();
			var current = json.readTree(result.getResponse().getContentAsByteArray()).get("id").asText();
			if (disputeId == null) disputeId = current;
			assertThat(current).isEqualTo(disputeId);
		}
		assertThat(settlementGate.earningEligible(fixture.appointmentId())).isFalse();
		mvc.perform(get("/api/v1/appointments/{id}/dispute", fixture.appointmentId())
				.with(actor(UUID.randomUUID(), "USER"))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/admin/appointment-disputes").with(actor(UUID.randomUUID(), "ADMIN")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));

		var admin = UUID.randomUUID();
		var resolve = "{\"outcome\":\"RELEASE_USER_CREDIT\",\"reasonCode\":\"TECHNICAL_FAILURE_CONFIRMED\"}";
		for (int attempt = 0; attempt < 2; attempt++) {
			mvc.perform(post("/api/v1/admin/appointment-disputes/{id}/resolve", disputeId)
					.with(actor(admin, "ADMIN")).header("If-Match", "\"0\"")
					.header("Idempotency-Key", "resolve-dispute-command-01")
					.contentType(MediaType.APPLICATION_JSON).content(resolve))
					.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
					.andExpect(jsonPath("$.settlementGated").value(true))
					.andExpect(jsonPath("$.creditAction").value("ADJUSTED_RELEASED"))
					.andExpect(jsonPath("$.priorSessionOutcome").value("COMPLETED"))
					.andExpect(jsonPath("$.resultingSessionOutcome").value("COMPLETED"));
		}
		assertThat(jdbc.sql("select state from service_credit where id=:id").param("id", fixture.creditId())
				.query(String.class).single()).isEqualTo("AVAILABLE");
		assertThat(settlementGate.earningEligible(fixture.appointmentId())).isFalse();
		assertThat(jdbc.sql("""
				select count(*) from service_credit_ledger
				where appointment_id=:id and event_type='ADJUSTED_RELEASED'
				""").param("id", fixture.appointmentId()).query(Long.class).single()).isOne();
	}

	@Test
	void upheldDisputeReopensTheEarningGateWithoutRewritingSettlement() throws Exception {
		var fixture = completedAppointment(Instant.now().minusSeconds(300));
		var opened = mvc.perform(post("/api/v1/appointments/{id}/dispute", fixture.appointmentId())
				.with(actor(fixture.userId(), "USER")).header("Idempotency-Key", "uphold-open-key-00001")
				.contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"OUTCOME_INCORRECT\"}"))
				.andExpect(status().isOk()).andReturn();
		var disputeId = json.readTree(opened.getResponse().getContentAsByteArray()).get("id").asText();
		mvc.perform(post("/api/v1/admin/appointment-disputes/{id}/resolve", disputeId)
				.with(actor(UUID.randomUUID(), "ADMIN")).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "uphold-resolve-key-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"outcome\":\"UPHOLD_RECORDED_OUTCOME\",\"reasonCode\":\"EVIDENCE_SUPPORTS_RECORDED_OUTCOME\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.creditAction").value("NONE"));

		assertThat(settlementGate.earningEligible(fixture.appointmentId())).isTrue();
		assertThat(jdbc.sql("select state from service_credit where id=:id").param("id", fixture.creditId())
				.query(String.class).single()).isEqualTo("CONSUMED");
	}

	@Test
	void specialistCanOpenAssignedOutcomeButCannotReadAnotherAppointment() throws Exception {
		var fixture = completedAppointment(Instant.now().minusSeconds(300));
		mvc.perform(post("/api/v1/specialist/appointments/{id}/dispute", fixture.appointmentId())
				.with(actor(fixture.specialistId(), "SPECIALIST"))
				.header("Idempotency-Key", "specialist-dispute-key-01")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"PARTICIPATION_EVIDENCE_INCORRECT\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.openedByRole").value("SPECIALIST"));
		mvc.perform(get("/api/v1/specialist/appointments/{id}/dispute", fixture.appointmentId())
				.with(actor(UUID.randomUUID(), "SPECIALIST"))).andExpect(status().isNotFound());
	}

	@Test
	void rejectsExpiredIneligibleAndInconsistentResolutionCommands() throws Exception {
		var expired = completedAppointment(Instant.now().minusSeconds(25 * 3_600));
		mvc.perform(post("/api/v1/appointments/{id}/dispute", expired.appointmentId())
				.with(actor(expired.userId(), "USER")).header("Idempotency-Key", "expired-dispute-key-001")
				.contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"OUTCOME_INCORRECT\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPOINTMENT_DISPUTE_WINDOW_EXPIRED"));

		var fixture = completedAppointment(Instant.now().minusSeconds(300));
		var opened = mvc.perform(post("/api/v1/appointments/{id}/dispute", fixture.appointmentId())
				.with(actor(fixture.userId(), "USER")).header("Idempotency-Key", "valid-dispute-key-00001")
				.contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"TECHNICAL_FAILURE\"}"))
				.andExpect(status().isOk()).andReturn();
		var disputeId = json.readTree(opened.getResponse().getContentAsByteArray()).get("id").asText();
		mvc.perform(post("/api/v1/admin/appointment-disputes/{id}/resolve", disputeId)
				.with(actor(UUID.randomUUID(), "ADMIN")).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "invalid-resolution-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"outcome\":\"UPHOLD_RECORDED_OUTCOME\",\"reasonCode\":\"TECHNICAL_FAILURE_CONFIRMED\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_DISPUTE_RESOLUTION_INVALID"));

		var bounded = completedAppointment(Instant.now().minusSeconds(300));
		mvc.perform(post("/api/v1/appointments/{id}/dispute", bounded.appointmentId())
				.with(actor(bounded.userId(), "USER")).header("Idempotency-Key", "unbounded-evidence-key-01")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"reasonCode":"TECHNICAL_FAILURE","evidenceType":"ACCESS_LOG",
						 "evidenceOccurredAt":"%s"}
						""".formatted(bounded.sessionStart().plusSeconds(7_200))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_DISPUTE_EVIDENCE_INVALID"));
	}

	@Test
	void concurrentOpenAndCreditReleaseProduceOneDisputeAndOneAdjustment() {
		var fixture = completedAppointment(Instant.now().minusSeconds(300));
		var openStart = new CountDownLatch(1);
		var request = new OpenAppointmentDisputeRequest(
				OpenAppointmentDisputeRequest.ReasonCode.TECHNICAL_FAILURE, null, null);
		var first = concurrent(openStart, () -> disputeService.open(fixture.userId(), "USER",
				fixture.appointmentId(), "concurrent-open-command-01", request));
		var second = concurrent(openStart, () -> disputeService.open(fixture.userId(), "USER",
				fixture.appointmentId(), "concurrent-open-command-02", request));
		openStart.countDown();
		var openResults = List.of(first.join(), second.join());
		assertThat(openResults).filteredOn(AppointmentDisputeResponse.class::isInstance).hasSize(1);
		assertThat(openResults).filteredOn(ApiException.class::isInstance).singleElement()
				.extracting(value -> ((ApiException) value).code()).isEqualTo("APPOINTMENT_DISPUTE_ALREADY_EXISTS");
		var dispute = (AppointmentDisputeResponse) openResults.stream()
				.filter(AppointmentDisputeResponse.class::isInstance).findFirst().orElseThrow();

		var resolveStart = new CountDownLatch(1);
		var resolution = new ResolveAppointmentDisputeRequest(
				ResolveAppointmentDisputeRequest.Outcome.RELEASE_USER_CREDIT,
				ResolveAppointmentDisputeRequest.ReasonCode.TECHNICAL_FAILURE_CONFIRMED);
		var resolveFirst = concurrent(resolveStart, () -> disputeService.resolve(UUID.randomUUID(), dispute.id(), 0,
				"concurrent-resolve-key-01", resolution));
		var resolveSecond = concurrent(resolveStart, () -> disputeService.resolve(UUID.randomUUID(), dispute.id(), 0,
				"concurrent-resolve-key-02", resolution));
		resolveStart.countDown();
		var resolutionResults = List.of(resolveFirst.join(), resolveSecond.join());
		assertThat(resolutionResults).filteredOn(AppointmentDisputeResponse.class::isInstance).hasSize(1);
		assertThat(resolutionResults).filteredOn(ApiException.class::isInstance).singleElement()
				.extracting(value -> ((ApiException) value).code()).isEqualTo("APPOINTMENT_DISPUTE_ALREADY_RESOLVED");
		assertThat(jdbc.sql("""
				select count(*) from service_credit_ledger
				where appointment_id=:id and event_type='ADJUSTED_RELEASED'
				""").param("id", fixture.appointmentId()).query(Long.class).single()).isOne();
	}

	private CompletableFuture<Object> concurrent(CountDownLatch start, java.util.function.Supplier<Object> action) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				start.await();
				return action.get();
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return interrupted;
			}
			catch (RuntimeException failure) {
				return failure;
			}
		});
	}

	private Fixture completedAppointment(Instant settledAt) {
		var userId = UUID.randomUUID();
		var specialistId = UUID.randomUUID();
		var appointmentId = UUID.randomUUID();
		var slotId = UUID.randomUUID();
		var periodId = UUID.randomUUID();
		var creditId = UUID.randomUUID();
		var start = settledAt.minusSeconds(3_700);
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Dispute specialist', 'Operational test profile', 5, 'Asia/Ho_Chi_Minh',
				 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", UUID.randomUUID()).update();
		jdbc.sql("""
				insert into availability_slot (
				 id, specialist_account_id, start_at, end_at, timezone, modality, status,
				 idempotency_key, withdrawn_at, created_at, updated_at
				) values (:id, :specialistId, :startAt, :endAt, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT',
				 'WITHDRAWN', :key, now(), now(), now())
				""").param("id", slotId).param("specialistId", specialistId).param("startAt", Timestamp.from(start))
				.param("endAt", Timestamp.from(start.plusSeconds(3_600))).param("key", "dispute-slot-" + slotId).update();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_policy_version, created_at, updated_at
				) values (:id, :userId, 'dispute-test-v1', 'PLUS', 'PAID', :reference,
				 :periodStart, :periodEnd, 1, 'consultation-credit-v1', now(), now())
				""").param("id", periodId).param("userId", userId).param("reference", "dispute-" + appointmentId)
				.param("periodStart", Timestamp.from(start.minusSeconds(86_400)))
				.param("periodEnd", Timestamp.from(start.plusSeconds(172_800))).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, appointment_id, created_at, updated_at)
				values (:id, :periodId, 1, 'CONSUMED', :appointmentId, now(), now())
				""").param("id", creditId).param("periodId", periodId).param("appointmentId", appointmentId).update();
		jdbc.sql("""
				insert into appointment (
				 id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id,
				 status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
				 requested_at, decision_deadline_at, idempotency_key, decided_at, decision_reason,
				 session_outcome, session_outcome_reason, session_policy_version, session_ended_at,
				 session_settled_at, completion_fact_id, created_at, updated_at
				) values (
				 :id, :userId, :specialistId, :slotId, :creditId,
				 'COMPLETED', 'IN_APP_CHAT', :startAt, :endAt, 'Asia/Ho_Chi_Minh',
				 :requestedAt, :deadline, :key, :decidedAt, 'SPECIALIST_ACCEPTED',
				 'COMPLETED', 'EVIDENCE_REQUIREMENTS_MET', 'chat-session-completion-v1', :endedAt,
				 :settledAt, :completionFactId, now(), now()
				)
				""").param("id", appointmentId).param("userId", userId).param("specialistId", specialistId)
				.param("slotId", slotId).param("creditId", creditId).param("startAt", Timestamp.from(start))
				.param("endAt", Timestamp.from(start.plusSeconds(3_600)))
				.param("requestedAt", Timestamp.from(start.minusSeconds(7_200)))
				.param("deadline", Timestamp.from(start.minusSeconds(3_600)))
				.param("key", "dispute-appointment-" + appointmentId)
				.param("decidedAt", Timestamp.from(start.minusSeconds(4_000)))
				.param("endedAt", Timestamp.from(start.plusSeconds(3_600)))
				.param("settledAt", Timestamp.from(settledAt)).param("completionFactId", UUID.randomUUID()).update();
		jdbc.sql("""
				insert into service_credit_ledger (
				 id, credit_id, account_id, event_type, appointment_id, idempotency_key, occurred_at
				) values (:id, :creditId, :userId, 'CONSUMED', :appointmentId, :key, :occurredAt)
				""").param("id", UUID.randomUUID()).param("creditId", creditId).param("userId", userId)
				.param("appointmentId", appointmentId).param("key", "settled-credit-" + appointmentId)
				.param("occurredAt", Timestamp.from(settledAt)).update();
		return new Fixture(appointmentId, userId, specialistId, creditId, start);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor actor(UUID id, String role) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of(role)))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role));
	}

	private record Fixture(UUID appointmentId, UUID userId, UUID specialistId, UUID creditId, Instant sessionStart) { }
}
