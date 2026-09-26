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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AppointmentDecisionIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired AppointmentDecisionService decisions;

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

		assertThat(historyCount(fixture.appointmentId())).isOne();
		assertThat(creditState(fixture.creditId())).isEqualTo("HELD");
		assertThat(releaseCount(fixture.appointmentId())).isZero();
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

		assertThat(historyCount(fixture.appointmentId())).isOne();
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
		assertThat(historyCount(fixture.appointmentId())).isZero();
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

		assertThat(historyCount(fixture.appointmentId())).isOne();
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
		assertThat(historyCount(fixture.appointmentId())).isOne();
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

	private String appointmentStatus(UUID appointmentId) {
		return jdbc.sql("select status from appointment where id=:id").param("id", appointmentId)
				.query(String.class).single();
	}

	private String creditState(UUID creditId) {
		return jdbc.sql("select state from service_credit where id=:id").param("id", creditId)
				.query(String.class).single();
	}

	private long historyCount(UUID appointmentId) {
		return jdbc.sql("select count(*) from appointment_status_history where appointment_id=:id")
				.param("id", appointmentId).query(Long.class).single();
	}

	private long releaseCount(UUID appointmentId) {
		return jdbc.sql("""
				select count(*) from service_credit_ledger
				where appointment_id=:id and event_type='RELEASED'
				""").param("id", appointmentId).query(Long.class).single();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor specialist(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"));
	}

	private record Fixture(UUID appointmentId, UUID userId, UUID specialistId, UUID creditId) {
	}
}
