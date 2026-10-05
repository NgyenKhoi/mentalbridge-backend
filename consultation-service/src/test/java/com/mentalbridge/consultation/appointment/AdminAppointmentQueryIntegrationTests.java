package com.mentalbridge.consultation.appointment;

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
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAppointmentQueryIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;

	@Test
	void adminFiltersAndPagesOnlyContentFreeOperationalFacts() throws Exception {
		var userId = UUID.randomUUID();
		var specialistId = approvedSpecialist();
		var first = appointment(userId, specialistId, Instant.parse("2026-10-10T10:00:00Z"), "CONFIRMED");
		var second = appointment(userId, specialistId, Instant.parse("2026-10-11T10:00:00Z"), "REQUESTED");

		var page = mvc.perform(get("/api/v1/admin/appointments").with(admin())
				.param("from", "2026-09-01T00:00:00Z").param("to", "2026-11-01T00:00:00Z")
				.param("status", "CONFIRMED").param("userAccountId", userId.toString()).param("limit", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.source").value("CONSULTATION"))
				.andExpect(jsonPath("$.dataState").value("CURRENT"))
				.andExpect(jsonPath("$.items[0].appointmentId").value(first.toString()))
				.andExpect(jsonPath("$.items[0].settlementState").value("HELD"))
				.andExpect(jsonPath("$.items[0].consultationBrief").doesNotExist())
				.andExpect(jsonPath("$.items[0].chatMessages").doesNotExist()).andReturn();

		var body = page.getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(body).doesNotContain("journal", "assessmentAnswers", "privateNotes");

		var firstPage = mvc.perform(get("/api/v1/admin/appointments").with(admin())
				.param("from", "2026-10-01T00:00:00Z").param("to", "2026-11-01T00:00:00Z")
				.param("limit", "1")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].appointmentId").value(second.toString()))
				.andExpect(jsonPath("$.nextCursor").isNotEmpty()).andReturn();
		var cursor = json.readTree(firstPage.getResponse().getContentAsByteArray()).get("nextCursor").asText();
		mvc.perform(get("/api/v1/admin/appointments").with(admin())
				.param("from", "2026-10-01T00:00:00Z").param("to", "2026-11-01T00:00:00Z")
				.param("limit", "1").param("cursor", cursor)).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].appointmentId").value(first.toString()))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void nonAdminAndInvalidOrUnboundedQueriesFailClosed() throws Exception {
		mvc.perform(get("/api/v1/admin/appointments").with(user())
				.param("from", "2026-09-01T00:00:00Z").param("to", "2026-11-01T00:00:00Z"))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/appointments").with(admin())
				.param("from", "2026-01-01T00:00:00Z").param("to", "2026-12-31T00:00:00Z"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ADMIN_APPOINTMENT_QUERY"));
		mvc.perform(get("/api/v1/admin/appointments").with(admin())
				.param("from", "2026-09-01T00:00:00Z").param("to", "2026-11-01T00:00:00Z")
				.param("status", "ANY_STATUS"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ADMIN_APPOINTMENT_QUERY"));
	}

	private UUID approvedSpecialist() {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (account_id, display_name, biography, years_experience, timezone,
				 approval_status, submitted_at, reviewed_at, reviewed_by)
				values (:id, 'Safe specialist', 'Operational fixture', 4, 'Asia/Ho_Chi_Minh',
				 'APPROVED', now(), now(), :admin)
				""").param("id", id).param("admin", UUID.randomUUID()).update();
		return id;
	}

	private UUID appointment(UUID userId, UUID specialistId, Instant start, String state) {
		var slotId = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (id, specialist_account_id, start_at, end_at, timezone, modality,
				 idempotency_key, created_at, updated_at)
				values (:id, :specialist, :start, :end, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :key, now(), now())
				""").param("id", slotId).param("specialist", specialistId).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3600))).param("key", "admin-slot-" + slotId).update();
		var periodId = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (id, account_id, plan_version, credit_policy_version,
				 package_code, source, source_reference, period_start, period_end, allocated_count,
				 created_at, updated_at)
				values (:id, :userId, :planVersion, 'consultation-credit-v2', 'PLUS', 'DEMO',
				 :reference, :start, :end, 4, now(), now())
				""").param("id", periodId).param("userId", userId).param("planVersion", "admin-plan-" + periodId)
				.param("reference", "admin-period-" + periodId)
				.param("start", Timestamp.from(start.minusSeconds(86400))).param("end", Timestamp.from(start.plusSeconds(86400))).update();
		var creditId = UUID.randomUUID();
		var appointmentId = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, appointment_id, created_at, updated_at)
				values (:id, :periodId, 1, 'HELD', :appointmentId, now(), now())
				""").param("id", creditId).param("periodId", periodId).param("appointmentId", appointmentId).update();
		jdbc.sql("""
				insert into appointment (id, user_account_id, specialist_account_id, availability_slot_id,
				 service_credit_id, status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
				 requested_at, decision_deadline_at, idempotency_key, created_at, updated_at,
				 decided_at, decision_reason)
				values (:id, :userId, :specialistId, :slotId, :creditId, :status, 'IN_APP_CHAT', :start, :end,
				 'Asia/Ho_Chi_Minh', :requestedAt, :deadline, :key, now(), now(),
				 case when :status='CONFIRMED' then now() else null end,
				 case when :status='CONFIRMED' then 'SPECIALIST_ACCEPTED' else null end)
				""").param("id", appointmentId).param("userId", userId).param("specialistId", specialistId)
				.param("slotId", slotId).param("creditId", creditId).param("status", state)
				.param("start", Timestamp.from(start)).param("end", Timestamp.from(start.plusSeconds(3600)))
				.param("requestedAt", Timestamp.from(start.minusSeconds(7200)))
				.param("deadline", Timestamp.from(start.minusSeconds(3600))).param("key", "admin-appointment-" + appointmentId).update();
		return appointmentId;
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
		return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user() {
		return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
