package com.mentalbridge.consultation.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

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
import com.mentalbridge.consultation.shared.ApiException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AppointmentRatingFlowIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired AppointmentRatingService ratings;

	@Test
	void ownerCreatesReadsAndEditsOneCurrentRatingWithAccurateAggregate() throws Exception {
		var specialistId = specialist();
		var first = appointment(specialistId, true);
		var second = appointment(specialistId, true);

		mvc.perform(put("/api/v1/appointments/{id}/rating", first.id()).with(user(first.userId()))
				.contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
				.andExpect(jsonPath("$.rating").value(5))
				.andExpect(jsonPath("$.specialistAggregate.averageRating").value(5.00))
				.andExpect(jsonPath("$.specialistAggregate.ratingCount").value(1));
		mvc.perform(put("/api/v1/appointments/{id}/rating", second.id()).with(user(second.userId()))
				.contentType(MediaType.APPLICATION_JSON).content("{\"rating\":3}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.specialistAggregate.averageRating").value(4.00))
				.andExpect(jsonPath("$.specialistAggregate.ratingCount").value(2));
		mvc.perform(put("/api/v1/appointments/{id}/rating", first.id()).with(user(first.userId()))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":1}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.specialistAggregate.averageRating").value(2.00));
		mvc.perform(get("/api/v1/appointments/{id}/rating", first.id()).with(user(first.userId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.rating").value(1))
				.andExpect(jsonPath("$.specialistAggregate.ratingCount").value(2));
		assertThat(jdbc.sql("select rating_sum from specialist_rating_aggregate where specialist_account_id=:id")
				.param("id", specialistId).query(Long.class).single()).isEqualTo(4);
	}

	@Test
	void rejectsWrongActorIneligibleAppointmentInvalidValueAndUnsafeOverwrite() throws Exception {
		var specialistId = specialist();
		var completed = appointment(specialistId, true);
		var upcoming = appointment(specialistId, false);

		mvc.perform(put("/api/v1/appointments/{id}/rating", completed.id()).with(user(UUID.randomUUID()))
				.contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}"))
				.andExpect(status().isNotFound());
		mvc.perform(put("/api/v1/appointments/{id}/rating", upcoming.id()).with(user(upcoming.userId()))
				.contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_RATEABLE"));
		mvc.perform(put("/api/v1/appointments/{id}/rating", completed.id()).with(user(completed.userId()))
				.contentType(MediaType.APPLICATION_JSON).content("{\"rating\":6}"))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/appointments/{id}/rating", completed.id()).with(user(completed.userId()))
				.contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4}"))
				.andExpect(status().isOk());
		mvc.perform(put("/api/v1/appointments/{id}/rating", completed.id()).with(user(completed.userId()))
				.contentType(MediaType.APPLICATION_JSON).content("{\"rating\":3}"))
				.andExpect(status().isPreconditionRequired())
				.andExpect(jsonPath("$.code").value("RATING_VERSION_REQUIRED"));
		mvc.perform(put("/api/v1/appointments/{id}/rating", completed.id()).with(user(completed.userId()))
				.header("If-Match", "\"9\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":3}"))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("RATING_VERSION_MISMATCH"));
	}

	@Test
	void concurrentFirstSaveKeepsOneCurrentRatingAndOneAggregateContribution() throws Exception {
		var specialistId = specialist();
		var appointment = appointment(specialistId, true);
		var ready = new CountDownLatch(2);
		var go = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> save(appointment, 5, ready, go));
			var second = executor.submit(() -> save(appointment, 4, ready, go));
			ready.await();
			go.countDown();
			assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("OK", "RATING_VERSION_REQUIRED");
		}
		assertThat(jdbc.sql("select count(*) from appointment_rating where appointment_id=:id")
				.param("id", appointment.id()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select rating_count from specialist_rating_aggregate where specialist_account_id=:id")
				.param("id", specialistId).query(Long.class).single()).isOne();
	}

	private String save(Fixture appointment, int value, CountDownLatch ready, CountDownLatch go) throws Exception {
		ready.countDown();
		go.await();
		try {
			ratings.save(appointment.userId(), appointment.id(), value, null);
			return "OK";
		}
		catch (ApiException exception) {
			return exception.code();
		}
	}

	private UUID specialist() {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Rating specialist', 'Rating test profile', 5, 'Asia/Ho_Chi_Minh',
				 'APPROVED', now(), now(), :admin)
				""").param("id", id).param("admin", UUID.randomUUID()).update();
		return id;
	}

	private Fixture appointment(UUID specialistId, boolean completed) {
		var id = UUID.randomUUID();
		var userId = UUID.randomUUID();
		var slotId = UUID.randomUUID();
		var periodId = UUID.randomUUID();
		var creditId = UUID.randomUUID();
		var start = Instant.now().minusSeconds(completed ? 7_200 : -86_400);
		jdbc.sql("""
				insert into availability_slot (
				 id, specialist_account_id, start_at, end_at, timezone, modality, status,
				 idempotency_key, withdrawn_at, created_at, updated_at
				) values (:id, :specialistId, :startAt, :endAt, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT',
				 'WITHDRAWN', :key, now(), now(), now())
				""").param("id", slotId).param("specialistId", specialistId)
				.param("startAt", Timestamp.from(start)).param("endAt", Timestamp.from(start.plusSeconds(3_600)))
				.param("key", "rating-slot-" + slotId).update();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_policy_version, created_at, updated_at
				) values (:id, :userId, 'rating-test-v1', 'PLUS', 'PAID', :reference,
				 :periodStart, :periodEnd, 1, 'consultation-credit-v1', now(), now())
				""").param("id", periodId).param("userId", userId).param("reference", "rating-" + id)
				.param("periodStart", Timestamp.from(start.minusSeconds(86_400)))
				.param("periodEnd", Timestamp.from(start.plusSeconds(86_400))).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, appointment_id, created_at, updated_at)
				values (:id, :periodId, 1, :state, :appointmentId, now(), now())
				""").param("id", creditId).param("periodId", periodId)
				.param("state", completed ? "CONSUMED" : "HELD").param("appointmentId", id).update();
		jdbc.sql("""
				insert into appointment (
				 id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id,
				 status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
				 requested_at, decision_deadline_at, idempotency_key, decided_at, decision_reason,
				 session_outcome, session_outcome_reason, session_policy_version, session_ended_at,
				 session_settled_at, completion_fact_id, created_at, updated_at
				) values (
				 :id, :userId, :specialistId, :slotId, :creditId,
				 :status, 'IN_APP_CHAT', :startAt, :endAt, 'Asia/Ho_Chi_Minh',
				 :requestedAt, :deadline, :key, :decidedAt, 'SPECIALIST_ACCEPTED',
				 :outcome, :outcomeReason, :policy, :endedAt,
				 :settledAt, :completionFactId, now(), now()
				)
				""").param("id", id).param("userId", userId).param("specialistId", specialistId)
				.param("slotId", slotId).param("creditId", creditId).param("status", completed ? "COMPLETED" : "CONFIRMED")
				.param("startAt", Timestamp.from(start)).param("endAt", Timestamp.from(start.plusSeconds(3_600)))
				.param("requestedAt", Timestamp.from(start.minusSeconds(7_200)))
				.param("deadline", Timestamp.from(start.minusSeconds(3_600)))
				.param("key", "rating-appointment-" + id).param("decidedAt", Timestamp.from(start.minusSeconds(4_000)))
				.param("outcome", completed ? "COMPLETED" : null)
				.param("outcomeReason", completed ? "EVIDENCE_REQUIREMENTS_MET" : null)
				.param("policy", completed ? "chat-session-completion-v1" : null)
				.param("endedAt", completed ? Timestamp.from(start.plusSeconds(3_600)) : null)
				.param("settledAt", completed ? Timestamp.from(start.plusSeconds(3_700)) : null)
				.param("completionFactId", completed ? UUID.randomUUID() : null).update();
		return new Fixture(id, userId);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("USER")))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private record Fixture(UUID id, UUID userId) { }
}
