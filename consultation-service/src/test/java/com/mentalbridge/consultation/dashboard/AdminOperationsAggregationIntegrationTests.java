package com.mentalbridge.consultation.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminOperationsAggregationIntegrationTests extends ConsultationTestProperties {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AdminOperationsService adminOperationsService;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void operationalSummaryAggregatesRealAppointmentsAndNoShowSessionOutcomesCorrectly() throws Exception {
		// 1. Setup Specialists in different approval states
		insertSpecialist("PENDING", null);
		var approvedSpecialist1 = insertSpecialist("APPROVED", null);
		insertSpecialist("APPROVED", null);
		insertSpecialist("REJECTED", "OUTSIDE_SUPPORTED_SCOPE");
		insertSpecialist("SUSPENDED", "POLICY_VIOLATION");

		var userId = UUID.randomUUID();
		var baseStart = Instant.parse("2026-10-15T09:00:00Z");

		// 2. Setup Appointments in different statuses and session outcomes
		// Standard lifecycle states
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(3600), "REQUESTED", null);
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(7200), "CONFIRMED", null);
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(10800), "IN_PROGRESS", null);
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(14400), "COMPLETED", "COMPLETED");
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(18000), "CANCELLED", null);
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(21600), "REJECTED", null);
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(25200), "EXPIRED", null);

		// SESSION_ENDED states with different session_outcome values (including no-shows)
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(28800), "SESSION_ENDED", "USER_NO_SHOW");
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(32400), "SESSION_ENDED", "SPECIALIST_NO_SHOW");
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(36000), "SESSION_ENDED", "BOTH_NO_SHOW");
		insertAppointment(userId, approvedSpecialist1, baseStart.plusSeconds(39600), "SESSION_ENDED", null);

		// 3. Directly verify AdminOperationsService SQL aggregation
		var summary = adminOperationsService.getOperationsSummary();

		assertThat(summary.source()).isEqualTo("CONSULTATION");
		assertThat(summary.asOf()).isNotNull();

		// Specialist breakdown assertions: total = 5 (1 pending, 2 active, 1 rejected, 1 suspended)
		var specialists = summary.specialists();
		assertThat(specialists.total()).isEqualTo(5);
		assertThat(specialists.pendingReview()).isEqualTo(1);
		assertThat(specialists.active()).isEqualTo(2);
		assertThat(specialists.rejected()).isEqualTo(1);
		assertThat(specialists.suspended()).isEqualTo(1);

		// Appointment breakdown assertions: total = 11
		var appointments = summary.appointments();
		assertThat(appointments.total()).isEqualTo(11);
		assertThat(appointments.requested()).isEqualTo(1);
		assertThat(appointments.confirmed()).isEqualTo(1);
		assertThat(appointments.inProgress()).isEqualTo(1);
		assertThat(appointments.completed()).isEqualTo(1);
		assertThat(appointments.cancelled()).isEqualTo(1);
		assertThat(appointments.rejected()).isEqualTo(1);
		assertThat(appointments.expired()).isEqualTo(1);
		assertThat(appointments.sessionEnded()).isEqualTo(4);

		// Session outcome no-show assertions
		assertThat(appointments.userNoShow()).isEqualTo(1);
		assertThat(appointments.specialistNoShow()).isEqualTo(1);
		assertThat(appointments.bothNoShow()).isEqualTo(1);

		// 4. Verify HTTP endpoint via MockMvc with ADMIN authorization
		var result = mvc.perform(get("/api/v1/admin/operations/summary").with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("CONSULTATION"))
				.andExpect(jsonPath("$.asOf").isNotEmpty())
				.andExpect(jsonPath("$.specialists.total").value(5))
				.andExpect(jsonPath("$.specialists.pendingReview").value(1))
				.andExpect(jsonPath("$.specialists.active").value(2))
				.andExpect(jsonPath("$.specialists.rejected").value(1))
				.andExpect(jsonPath("$.specialists.suspended").value(1))
				.andExpect(jsonPath("$.appointments.total").value(11))
				.andExpect(jsonPath("$.appointments.requested").value(1))
				.andExpect(jsonPath("$.appointments.confirmed").value(1))
				.andExpect(jsonPath("$.appointments.inProgress").value(1))
				.andExpect(jsonPath("$.appointments.completed").value(1))
				.andExpect(jsonPath("$.appointments.cancelled").value(1))
				.andExpect(jsonPath("$.appointments.rejected").value(1))
				.andExpect(jsonPath("$.appointments.expired").value(1))
				.andExpect(jsonPath("$.appointments.sessionEnded").value(4))
				.andExpect(jsonPath("$.appointments.userNoShow").value(1))
				.andExpect(jsonPath("$.appointments.specialistNoShow").value(1))
				.andExpect(jsonPath("$.appointments.bothNoShow").value(1))
				.andExpect(jsonPath("$.appointments.disputed").doesNotExist())
				.andReturn();

		var body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("journal", "assessmentAnswers", "chatBody", "privateNotes");
	}

	private UUID insertSpecialist(String approvalStatus, String reasonCode) {
		var id = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (account_id, display_name, biography, years_experience, timezone,
				 approval_status, submitted_at, reviewed_at, reviewed_by, decision_reason_code)
				values (:id, 'Test Specialist', 'Bio fixture for operations test', 5, 'Asia/Ho_Chi_Minh',
				 :status,
				 case when :status = 'PENDING' then null else now() end,
				 case when :status = 'PENDING' then null else now() end,
				 case when :status = 'PENDING' then null else :adminId end,
				 :reasonCode)
				""")
				.param("id", id)
				.param("status", approvalStatus)
				.param("adminId", adminId)
				.param("reasonCode", reasonCode)
				.update();
		return id;
	}

	private UUID insertAppointment(UUID userId, UUID specialistId, Instant start, String status, String sessionOutcome) {
		var slotId = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (id, specialist_account_id, start_at, end_at, timezone, modality,
				 idempotency_key, created_at, updated_at)
				values (:id, :specialist, :start, :end, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :key, now(), now())
				""")
				.param("id", slotId)
				.param("specialist", specialistId)
				.param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3600)))
				.param("key", "op-slot-" + slotId)
				.update();

		var periodId = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (id, account_id, plan_version, credit_policy_version,
				 package_code, source, source_reference, period_start, period_end, allocated_count,
				 created_at, updated_at)
				values (:id, :userId, :planVersion, 'consultation-credit-v2', 'PLUS', 'DEMO',
				 :reference, :start, :end, 4, now(), now())
				""")
				.param("id", periodId)
				.param("userId", userId)
				.param("planVersion", "op-plan-" + periodId)
				.param("reference", "op-period-" + periodId)
				.param("start", Timestamp.from(start.minusSeconds(86400)))
				.param("end", Timestamp.from(start.plusSeconds(86400)))
				.update();

		var creditId = UUID.randomUUID();
		var appointmentId = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, appointment_id, created_at, updated_at)
				values (:id, :periodId, 1, 'HELD', :appointmentId, now(), now())
				""")
				.param("id", creditId)
				.param("periodId", periodId)
				.param("appointmentId", appointmentId)
				.update();

		var isCancelled = "CANCELLED".equals(status);
		var cancelledAt = isCancelled ? Timestamp.from(start.minusSeconds(1800)) : null;
		var cancellationReason = isCancelled ? "USER_CANCELLED" : null;
		var cancelledBy = isCancelled ? userId : null;
		var cancellationCreditOutcome = isCancelled ? "RELEASED" : null;

		var completionFactId = "COMPLETED".equals(status) ? UUID.randomUUID() : null;
		var sessionPolicyVersion = ("SESSION_ENDED".equals(status) || "COMPLETED".equals(status)) ? "chat-session-completion-v1" : null;
		var sessionEndedAt = ("SESSION_ENDED".equals(status) || "COMPLETED".equals(status)) ? Timestamp.from(start.plusSeconds(3600)) : null;
		var sessionSettledAt = (sessionOutcome != null && ("SESSION_ENDED".equals(status) || "COMPLETED".equals(status))) ? Timestamp.from(start.plusSeconds(3600)) : null;
		var sessionOutcomeReason = "COMPLETED".equals(sessionOutcome) ? "EVIDENCE_REQUIREMENTS_MET" : (sessionOutcome != null ? "NO_SHOW_EVALUATED" : null);

		jdbc.sql("""
				insert into appointment (id, user_account_id, specialist_account_id, availability_slot_id,
				 service_credit_id, status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
				 requested_at, decision_deadline_at, idempotency_key, created_at, updated_at,
				 decided_at, decision_reason, session_policy_version, session_ended_at, session_settled_at,
				 session_outcome, session_outcome_reason, completion_fact_id,
				 cancelled_at, cancellation_reason, cancelled_by, cancellation_credit_outcome)
				values (:id, :userId, :specialistId, :slotId, :creditId, :status, 'IN_APP_CHAT', :start, :end,
				 'Asia/Ho_Chi_Minh', :requestedAt, :deadline, :key, now(), now(),
				 case
				   when :status = 'REQUESTED' then null
				   when :status = 'REJECTED' then now()
				   when :status = 'EXPIRED' then now()
				   else now()
				 end,
				 case
				   when :status = 'REQUESTED' then null
				   when :status = 'REJECTED' then 'SPECIALIST_REJECTED'
				   when :status = 'EXPIRED' then 'DECISION_DEADLINE_EXPIRED'
				   else 'SPECIALIST_ACCEPTED'
				 end,
				 :sessionPolicyVersion, :sessionEndedAt, :sessionSettledAt,
				 :sessionOutcome, :sessionOutcomeReason, :completionFactId,
				 :cancelledAt, :cancellationReason, :cancelledBy, :cancellationCreditOutcome)
				""")
				.param("id", appointmentId)
				.param("userId", userId)
				.param("specialistId", specialistId)
				.param("slotId", slotId)
				.param("creditId", creditId)
				.param("status", status)
				.param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3600)))
				.param("requestedAt", Timestamp.from(start.minusSeconds(7200)))
				.param("deadline", Timestamp.from(start.minusSeconds(3600)))
				.param("key", "op-appt-" + appointmentId)
				.param("sessionPolicyVersion", sessionPolicyVersion)
				.param("sessionEndedAt", sessionEndedAt)
				.param("sessionSettledAt", sessionSettledAt)
				.param("sessionOutcome", sessionOutcome)
				.param("sessionOutcomeReason", sessionOutcomeReason)
				.param("completionFactId", completionFactId)
				.param("cancelledAt", cancelledAt)
				.param("cancellationReason", cancellationReason)
				.param("cancelledBy", cancelledBy)
				.param("cancellationCreditOutcome", cancellationCreditOutcome)
				.update();

		return appointmentId;
	}

	private RequestPostProcessor admin() {
		return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}
}

