package com.mentalbridge.consultation.analytics;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@Import({ TestcontainersConfiguration.class, SpecialistAnalyticsIntegrationTests.FixedClockConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class SpecialistAnalyticsIntegrationTests extends ConsultationTestProperties {

	private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
	private static final Instant FROM = NOW.minusSeconds(14 * 86_400L);

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;

	@Test
	void returnsPeriodFactsFromImmutableLifecycleHistoryWithoutSensitiveDataOrDemoFinancials() throws Exception {
		var specialistId = profile("APPROVED");
		var utilizedSlot = slot(specialistId, NOW.minusSeconds(5 * 86_400L));
		slot(specialistId, NOW.minusSeconds(3 * 86_400L));
		var cancelled = appointment(specialistId, utilizedSlot, "CANCELLED", "USER_CANCELLED", null);
		history(cancelled, null, "REQUESTED", "APPOINTMENT_REQUESTED", NOW.minusSeconds(8 * 86_400L));
		history(cancelled, "REQUESTED", "CONFIRMED", "SPECIALIST_ACCEPTED", NOW.minusSeconds(7 * 86_400L));
		history(cancelled, "CONFIRMED", "CANCELLED", "USER_CANCELLED", NOW.minusSeconds(6 * 86_400L));
		var noShow = appointment(specialistId, slot(specialistId, NOW.minusSeconds(2 * 86_400L)),
				"SESSION_ENDED", null, "USER_NO_SHOW");
		history(noShow, null, "REQUESTED", "APPOINTMENT_REQUESTED", NOW.minusSeconds(4 * 86_400L));
		history(noShow, "REQUESTED", "CONFIRMED", "SPECIALIST_ACCEPTED", NOW.minusSeconds(3 * 86_400L));
		var rescheduled = appointment(specialistId, slot(specialistId, NOW.minusSeconds(86_400L)),
				"CANCELLED", "USER_RESCHEDULED", null);
		history(rescheduled, null, "REQUESTED", "APPOINTMENT_REQUESTED", NOW.minusSeconds(2 * 86_400L));
		history(rescheduled, "REQUESTED", "CANCELLED", "USER_RESCHEDULED", NOW.minusSeconds(86_400L));
		rating(specialistId, 4, 18);

		mvc.perform(get("/api/v1/specialist/analytics")
				.param("from", FROM.toString()).param("to", NOW.toString()).with(specialist(specialistId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("CONSULTATION"))
				.andExpect(jsonPath("$.period.from").value(FROM.toString()))
				.andExpect(jsonPath("$.period.to").value(NOW.toString()))
				.andExpect(jsonPath("$.availability.publishedSlotCount").value(4))
				.andExpect(jsonPath("$.availability.utilizedSlotCount").value(2))
				.andExpect(jsonPath("$.availability.unusedSlotCount").value(2))
				.andExpect(jsonPath("$.availability.utilizationRate").value(50.00))
				.andExpect(jsonPath("$.appointments.requestedCount").value(3))
				.andExpect(jsonPath("$.appointments.acceptedCount").value(2))
				.andExpect(jsonPath("$.appointments.cancelledCount").value(1))
				.andExpect(jsonPath("$.appointments.rescheduledCount").value(1))
				.andExpect(jsonPath("$.appointments.userNoShowCount").value(1))
				.andExpect(jsonPath("$.rating.averageRating").value(4.50))
				.andExpect(jsonPath("$.financials.state").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.financials.currency").isEmpty())
				.andExpect(content().string(not(containsString("phq"))))
				.andExpect(content().string(not(containsString("journal"))))
				.andExpect(content().string(not(containsString("userAccountId"))));
	}

	@Test
	void suspendedSpecialistRetainsOwnHistoryWithTruthfulOperationalState() throws Exception {
		var specialistId = profile("SUSPENDED");
		var appointment = appointment(specialistId, slot(specialistId, NOW.minusSeconds(86_400)),
				"CANCELLED", "SPECIALIST_SUSPENDED", null);
		history(appointment, "CONFIRMED", "CANCELLED", "SPECIALIST_SUSPENDED", NOW.minusSeconds(43_200));

		mvc.perform(get("/api/v1/specialist/analytics").with(specialist(specialistId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationalStatus").value("SUSPENDED"))
				.andExpect(jsonPath("$.appointments.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.appointments.cancelledCount").value(1));
	}

	@Test
	void unapprovedSpecialistReceivesOnlyBlockedSections() throws Exception {
		var specialistId = profile("PENDING");

		mvc.perform(get("/api/v1/specialist/analytics").with(specialist(specialistId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.operationalStatus").value("PENDING_APPROVAL"))
				.andExpect(jsonPath("$.availability.state").value("BLOCKED"))
				.andExpect(jsonPath("$.availability.publishedSlotCount").isEmpty())
				.andExpect(jsonPath("$.appointments.state").value("BLOCKED"))
				.andExpect(jsonPath("$.rating.state").value("BLOCKED"))
				.andExpect(jsonPath("$.financials.state").value("BLOCKED"));
	}

	@Test
	void validatesBoundedPeriodAndSpecialistRole() throws Exception {
		var specialistId = profile("APPROVED");
		mvc.perform(get("/api/v1/specialist/analytics")
				.param("from", NOW.minusSeconds(400 * 86_400L).toString()).param("to", NOW.toString())
				.with(specialist(specialistId)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SPECIALIST_ANALYTICS_PERIOD_INVALID"));
		mvc.perform(get("/api/v1/specialist/analytics").with(user(UUID.randomUUID())))
				.andExpect(status().isForbidden());
	}

	private UUID profile(String status) {
		var id = UUID.randomUUID();
		var reviewed = status.equals("APPROVED") || status.equals("SUSPENDED");
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by, decision_reason_code
				) values (:id, 'Analytics specialist', 'Operational analytics test profile', 5,
				 'Asia/Ho_Chi_Minh', :status, :submittedAt, :reviewedAt, :reviewedBy, :reason)
				""").param("id", id).param("status", status)
				.param("submittedAt", database(NOW.minusSeconds(100_000)))
				.param("reviewedAt", reviewed ? database(NOW.minusSeconds(90_000)) : null)
				.param("reviewedBy", reviewed ? UUID.randomUUID() : null)
				.param("reason", status.equals("SUSPENDED") ? "QUALITY_REVIEW_REQUIRED" : null).update();
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
				.param("endAt", database(start.plusSeconds(3_600))).param("key", "analytics-slot-" + id)
				.param("now", database(FROM.minusSeconds(86_400))).update();
		return id;
	}

	private UUID appointment(UUID specialistId, UUID slotId, String status,
			String cancellationReason, String sessionOutcome) {
		var appointmentId = UUID.randomUUID();
		var userId = UUID.randomUUID();
		var periodId = UUID.randomUUID();
		var creditId = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, created_at, updated_at
				) values (:id, :userId, :planVersion, 'consultation-credit-v1', 'PLUS', 'DEMO',
				 :reference, :periodStart, :periodEnd, 1, :now, :now)
				""").param("id", periodId).param("userId", userId).param("planVersion", "analytics-" + periodId)
				.param("reference", "analytics-" + periodId).param("periodStart", database(FROM.minusSeconds(86_400)))
				.param("periodEnd", database(NOW.plusSeconds(86_400))).param("now", database(FROM)).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, appointment_id, created_at, updated_at)
				values (:id, :periodId, 1, 'HELD', :appointmentId, :now, :now)
				""").param("id", creditId).param("periodId", periodId).param("appointmentId", appointmentId)
				.param("now", database(FROM)).update();
		var cancelled = status.equals("CANCELLED");
		var settled = status.equals("SESSION_ENDED");
		jdbc.sql("""
				insert into appointment (
				 id, availability_slot_id, service_credit_id, user_account_id, specialist_account_id,
				 status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
				 requested_at, decision_deadline_at, idempotency_key, decided_at, decision_reason,
				 cancelled_at, cancelled_by, cancellation_reason, cancellation_credit_outcome,
				 session_outcome, session_outcome_reason, session_policy_version, session_ended_at,
				 session_settled_at, created_at, updated_at
				) select :id, :slotId, :creditId, :userId, :specialistId, :status, 'IN_APP_CHAT',
				 s.start_at, s.end_at, s.timezone, :requestedAt, :deadline, :key, :decidedAt,
				 'SPECIALIST_ACCEPTED', :cancelledAt, :cancelledBy, :cancellationReason, :creditOutcome,
				 :sessionOutcome, :sessionReason, :sessionPolicy, :sessionEndedAt, :sessionSettledAt, :now, :now
				from availability_slot s where s.id=:slotId
				""").param("id", appointmentId).param("slotId", slotId).param("creditId", creditId)
				.param("userId", userId).param("specialistId", specialistId).param("status", status)
				.param("requestedAt", database(FROM.plusSeconds(3_600))).param("deadline", database(FROM.plusSeconds(7_200)))
				.param("key", "analytics-appointment-" + appointmentId).param("decidedAt", database(FROM.plusSeconds(5_400)))
				.param("cancelledAt", cancelled ? database(NOW.minusSeconds(6 * 86_400L)) : null)
				.param("cancelledBy", cancelled ? (cancellationReason.equals("SPECIALIST_SUSPENDED") ? UUID.randomUUID() : userId) : null)
				.param("cancellationReason", cancellationReason)
				.param("creditOutcome", cancelled ? cancellationCreditOutcome(cancellationReason) : null)
				.param("sessionOutcome", sessionOutcome)
				.param("sessionReason", settled ? "USER_ZERO_EVIDENCE" : null)
				.param("sessionPolicy", settled ? "chat-session-completion-v1" : null)
				.param("sessionEndedAt", settled ? database(NOW.minusSeconds(86_400 + 3_600)) : null)
				.param("sessionSettledAt", settled ? database(NOW.minusSeconds(86_400)) : null)
				.param("now", database(FROM)).update();
		return appointmentId;
	}

	private void history(UUID appointmentId, String fromStatus, String toStatus, String reason, Instant changedAt) {
		jdbc.sql("""
				insert into appointment_status_history (
				 id, appointment_id, from_status, to_status, changed_by, reason, idempotency_key, changed_at,
				 credit_outcome
				) values (:id, :appointmentId, :fromStatus, :toStatus, :changedBy, :reason, :key, :changedAt,
				 :creditOutcome)
				""").param("id", UUID.randomUUID()).param("appointmentId", appointmentId)
				.param("fromStatus", fromStatus).param("toStatus", toStatus).param("reason", reason)
				.param("changedBy", toStatus.equals("CANCELLED") ? UUID.randomUUID() : null)
				.param("key", "analytics-history-" + UUID.randomUUID()).param("changedAt", database(changedAt))
				.param("creditOutcome", toStatus.equals("CANCELLED") ? cancellationCreditOutcome(reason) : null).update();
	}

	private String cancellationCreditOutcome(String reason) {
		return reason.equals("USER_RESCHEDULED") ? "TRANSFERRED_TO_REPLACEMENT" : "RELEASED";
	}

	private void rating(UUID specialistId, long count, long sum) {
		jdbc.sql("""
				insert into specialist_rating_aggregate (
				 specialist_account_id, rating_count, rating_sum, updated_at
				) values (:specialistId, :count, :sum, :now)
				""").param("specialistId", specialistId).param("count", count).param("sum", sum)
				.param("now", database(NOW)).update();
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
		Clock analyticsTestClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
