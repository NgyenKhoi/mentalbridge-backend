package com.mentalbridge.consultation.dashboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import({ TestcontainersConfiguration.class, SpecialistDashboardIntegrationTests.FixedClockConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class SpecialistDashboardIntegrationTests extends ConsultationTestProperties {

	private static final Instant NOW = Instant.parse("2026-10-03T02:00:00Z");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;

	@Test
	void approvedSpecialistReceivesBoundedCurrentOperationalFacts() throws Exception {
		var specialistId = profile("APPROVED");
		var todayAppointment = appointment(specialistId, "CONFIRMED", NOW.plusSeconds(3_600),
				NOW.minusSeconds(900));
		appointment(specialistId, "REQUESTED", NOW.plusSeconds(86_400), NOW.plusSeconds(7_200));
		var otherSpecialist = profile("APPROVED");
		var unauthorizedAppointment = appointment(otherSpecialist, "CONFIRMED", NOW.plusSeconds(5_400),
				NOW.minusSeconds(900));
		for (int index = 0; index < 7; index++) {
			slot(specialistId, NOW.plusSeconds(172_800L + (index * 7_200L)));
		}

		mvc.perform(get("/api/v1/specialist/dashboard").with(specialist(specialistId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("CONSULTATION"))
				.andExpect(jsonPath("$.operationalStatus").value("READY"))
				.andExpect(jsonPath("$.profile.displayName").value("Dashboard specialist"))
				.andExpect(jsonPath("$.todayConfirmedSessions.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.todayConfirmedSessions.localDate").value("2026-10-03"))
				.andExpect(jsonPath("$.todayConfirmedSessions.count").value(1))
				.andExpect(jsonPath("$.todayConfirmedSessions.items[0].appointmentId")
						.value(todayAppointment.toString()))
				.andExpect(jsonPath("$.todayConfirmedSessions.items[0].source").value("CONSULTATION"))
				.andExpect(jsonPath("$.todayConfirmedSessions.items[0].asOf").exists())
				.andExpect(jsonPath("$.todayConfirmedSessions.items[0].userAccountId").doesNotExist())
				.andExpect(jsonPath("$.pendingAppointmentRequests.count").value(1))
				.andExpect(jsonPath("$.nextAppointment.item.appointmentId").value(todayAppointment.toString()))
				.andExpect(jsonPath("$.availability.count").value(7))
				.andExpect(jsonPath("$.availability.items.length()").value(5))
				.andExpect(jsonPath("$.actionRequired[0].type").value("REVIEW_APPOINTMENT_REQUESTS"))
				.andExpect(content().string(not(containsString(unauthorizedAppointment.toString()))));
	}

	@Test
	void nonApprovedAndMissingProfilesFailClosedWithoutWorkloadFacts() throws Exception {
		var pendingId = profile("PENDING");
		slot(pendingId, NOW.plusSeconds(86_400));

		mvc.perform(get("/api/v1/specialist/dashboard").with(specialist(pendingId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationalStatus").value("PENDING_APPROVAL"))
				.andExpect(jsonPath("$.todayConfirmedSessions.state").value("BLOCKED"))
				.andExpect(jsonPath("$.todayConfirmedSessions.count").value(0))
				.andExpect(jsonPath("$.availability.state").value("BLOCKED"))
				.andExpect(jsonPath("$.availability.count").value(0))
				.andExpect(jsonPath("$.actionRequired[0].type").value("AWAIT_PROFILE_APPROVAL"));

		mvc.perform(get("/api/v1/specialist/dashboard").with(specialist(UUID.randomUUID())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationalStatus").value("PROFILE_REQUIRED"))
				.andExpect(jsonPath("$.profile.state").value("EMPTY"))
				.andExpect(jsonPath("$.actionRequired[0].type").value("COMPLETE_PROFILE"));

		var suspendedId = profile("SUSPENDED");
		appointment(suspendedId, "CONFIRMED", NOW.plusSeconds(3_600), NOW.minusSeconds(900));
		mvc.perform(get("/api/v1/specialist/dashboard").with(specialist(suspendedId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationalStatus").value("SUSPENDED"))
				.andExpect(jsonPath("$.todayConfirmedSessions.state").value("BLOCKED"))
				.andExpect(jsonPath("$.todayConfirmedSessions.count").value(0))
				.andExpect(jsonPath("$.nextAppointment.state").value("BLOCKED"))
				.andExpect(jsonPath("$.nextAppointment.item").doesNotExist())
				.andExpect(jsonPath("$.actionRequired[0].type").value("CONTACT_SUPPORT"));
	}

	@Test
	void dashboardRequiresTheSpecialistRole() throws Exception {
		mvc.perform(get("/api/v1/specialist/dashboard"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/specialist/dashboard").with(user(UUID.randomUUID())))
				.andExpect(status().isForbidden());
	}

	private UUID profile(String status) {
		var id = UUID.randomUUID();
		var reviewed = status.equals("APPROVED") || status.equals("SUSPENDED");
		var reason = status.equals("SUSPENDED") ? "QUALITY_REVIEW_REQUIRED" : null;
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by, decision_reason_code
				) values (:id, 'Dashboard specialist', 'Operational dashboard test profile', 5,
				 'Asia/Ho_Chi_Minh', :status, :submittedAt, :reviewedAt, :reviewedBy, :reason)
				""").param("id", id).param("status", status)
				.param("submittedAt", reviewed ? database(NOW.minusSeconds(86_400)) : null)
				.param("reviewedAt", reviewed ? database(NOW.minusSeconds(43_200)) : null)
				.param("reviewedBy", reviewed ? UUID.randomUUID() : null).param("reason", reason).update();
		return id;
	}

	private UUID slot(UUID specialistId, Instant start) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (
				 id, specialist_account_id, start_at, end_at, timezone, modality,
				 idempotency_key, created_at, updated_at
				) values (:id, :specialistId, :startAt, :endAt, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT',
				 :key, :now, :now)
				""").param("id", id).param("specialistId", specialistId).param("startAt", database(start))
				.param("endAt", database(start.plusSeconds(3_600))).param("key", "dashboard-slot-" + id)
				.param("now", database(NOW)).update();
		return id;
	}

	private UUID appointment(UUID specialistId, String status, Instant start, Instant deadline) {
		var appointmentId = UUID.randomUUID();
		var userId = UUID.randomUUID();
		var slotId = slot(specialistId, start);
		var periodId = UUID.randomUUID();
		var creditId = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, created_at, updated_at
				) values (:id, :userId, :planVersion, 'consultation-credit-v1', 'PLUS', 'DEMO',
				 :reference, :periodStart, :periodEnd, 1, :now, :now)
				""").param("id", periodId).param("userId", userId).param("planVersion", "dashboard-" + periodId)
				.param("reference", "dashboard-" + periodId).param("periodStart", database(NOW.minusSeconds(86_400)))
				.param("periodEnd", database(NOW.plusSeconds(2_592_000))).param("now", database(NOW)).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, appointment_id, created_at, updated_at)
				values (:id, :periodId, 1, 'HELD', :appointmentId, :now, :now)
				""").param("id", creditId).param("periodId", periodId).param("appointmentId", appointmentId)
				.param("now", database(NOW)).update();
		var confirmed = status.equals("CONFIRMED");
		jdbc.sql("""
				insert into appointment (
				 id, availability_slot_id, service_credit_id, user_account_id, specialist_account_id,
				 status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
				 requested_at, decision_deadline_at, idempotency_key, decided_at, decision_reason,
				 created_at, updated_at
				) values (:id, :slotId, :creditId, :userId, :specialistId, :status, 'IN_APP_CHAT',
				 :startAt, :endAt, 'Asia/Ho_Chi_Minh', :requestedAt, :deadline, :key,
				 :decidedAt, :decisionReason, :now, :now)
				""").param("id", appointmentId).param("slotId", slotId).param("creditId", creditId)
				.param("userId", userId).param("specialistId", specialistId).param("status", status)
				.param("startAt", database(start)).param("endAt", database(start.plusSeconds(3_600)))
				.param("requestedAt", database(deadline.minusSeconds(3_600))).param("deadline", database(deadline))
				.param("key", "dashboard-appointment-" + appointmentId)
				.param("decidedAt", confirmed ? database(NOW.minusSeconds(1_800)) : null)
				.param("decisionReason", confirmed ? "SPECIALIST_ACCEPTED" : null)
				.param("now", database(NOW)).update();
		return appointmentId;
	}

	private OffsetDateTime database(Instant value) {
		return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	private RequestPostProcessor specialist(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", java.util.List.of("SPECIALIST")))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"));
	}

	private RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", java.util.List.of("USER")))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {
		@Bean
		@Primary
		Clock dashboardTestClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
