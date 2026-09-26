package com.mentalbridge.consultation.appointment;

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
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.credits.CreditEventType;
import com.mentalbridge.consultation.credits.ServiceCreditService;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AppointmentRequestIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired ServiceCreditService credits;

	@Test
	void paidUserRequestsExactChatSlotAndReloadsHeldCreditSnapshot() throws Exception {
		var userId = paidUser("PLUS");
		var slotId = chatSlot(Instant.now().plusSeconds(86_400));
		var first = mvc.perform(post("/api/v1/appointments").with(user(userId))
				.header("Idempotency-Key", "appointment-request-0001").contentType(MediaType.APPLICATION_JSON)
				.content(body(slotId, "IN_APP_CHAT"))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("REQUESTED"))
				.andExpect(jsonPath("$.slotId").value(slotId.toString()))
				.andExpect(jsonPath("$.heldCreditId").isNotEmpty()).andReturn();
		var appointmentId = json.readTree(first.getResponse().getContentAsByteArray()).get("id").asText();

		mvc.perform(post("/api/v1/appointments").with(user(userId))
				.header("Idempotency-Key", "appointment-request-0001").contentType(MediaType.APPLICATION_JSON)
				.content(body(slotId, "IN_APP_CHAT"))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(appointmentId));
		mvc.perform(get("/api/v1/appointments").with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].decisionDeadlineAt").isNotEmpty());
		assertThat(jdbc.sql("select state from service_credit where appointment_id=:id")
				.param("id", UUID.fromString(appointmentId)).query(String.class).single()).isEqualTo("HELD");
	}

	@Test
	void freeWrongModeStaleLeadTimeAndUnavailableCreditFailStably() throws Exception {
		var slotId = chatSlot(Instant.now().plusSeconds(86_400));
		mvc.perform(post("/api/v1/appointments").with(user(UUID.randomUUID()))
				.header("Idempotency-Key", "free-request-key-0001").contentType(MediaType.APPLICATION_JSON)
				.content(body(slotId, "IN_APP_CHAT"))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PAID_PLAN_REQUIRED"));

		var paid = paidUser("PLUS");
		mvc.perform(post("/api/v1/appointments").with(user(paid))
				.header("Idempotency-Key", "wrong-mode-key-00001").contentType(MediaType.APPLICATION_JSON)
				.content(body(slotId, "IN_APP_VIDEO"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_MODALITY_MISMATCH"));

		var soon = chatSlot(Instant.now().plusSeconds(3_600));
		mvc.perform(post("/api/v1/appointments").with(user(paid))
				.header("Idempotency-Key", "short-lead-key-00001").contentType(MediaType.APPLICATION_JSON)
				.content(body(soon, "IN_APP_CHAT"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_LEAD_TIME_INVALID"));
	}

	@Test
	void bookableSlotsClampRequestedFromToMinimumLeadTime() throws Exception {
		var now = Instant.now();
		var tooSoon = chatSlot(now.plusSeconds(10_800));
		var bookable = chatSlot(now.plusSeconds(18_000));

		mvc.perform(get("/api/v1/bookable-slots").with(user(UUID.randomUUID()))
				.param("from", now.toString()).param("to", now.plusSeconds(86_400).toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(tooSoon)).isEmpty())
				.andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(bookable)).isNotEmpty());
	}

	@Test
	void concurrentUsersCannotDoubleHoldOneSlot() throws Exception {
		var first = paidUser("PLUS");
		var second = paidUser("PLUS");
		var slotId = chatSlot(Instant.now().plusSeconds(172_800));
		var ready = new CountDownLatch(2);
		var go = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var users = List.of(first, second);
			var futures = users.stream().map(userId -> executor.submit(() -> {
				ready.countDown();
				go.await();
				return mvc.perform(post("/api/v1/appointments").with(user(userId))
						.header("Idempotency-Key", "concurrent-request-" + userId)
						.contentType(MediaType.APPLICATION_JSON).content(body(slotId, "IN_APP_CHAT")))
						.andReturn().getResponse().getStatus();
			})).toList();
			ready.await();
			go.countDown();
			assertThat(futures.stream().map(future -> {
				try { return future.get(); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).toList()).containsExactlyInAnyOrder(201, 409);
		}
	}

	@Test
	void plusReservationCapIsDistinctFromRemainingCreditsAndTerminalStateFreesCapacity() throws Exception {
		var userId = paidUser("PLUS");
		var first = request(userId, chatSlot(Instant.now().plusSeconds(86_400)), "cap-request-first-0001", null);
		request(userId, chatSlot(Instant.now().plusSeconds(90_000)), "cap-request-second-001", null);

		mvc.perform(post("/api/v1/appointments").with(user(userId))
				.header("Idempotency-Key", "cap-request-third-0001").contentType(MediaType.APPLICATION_JSON)
				.content(body(chatSlot(Instant.now().plusSeconds(93_600)), "IN_APP_CHAT")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_RESERVATION_LIMIT_REACHED"));
		mvc.perform(get("/api/v1/service-credits").with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.balance.available").value(2))
				.andExpect(jsonPath("$.reservationCapacity.active").value(2))
				.andExpect(jsonPath("$.reservationCapacity.maximum").value(2))
				.andExpect(jsonPath("$.reservationCapacity.remaining").value(0));

		var firstId = UUID.fromString(json.readTree(first.getResponse().getContentAsByteArray()).get("id").asText());
		var firstCredit = jdbc.sql("select service_credit_id from appointment where id=:id")
				.param("id", firstId).query(UUID.class).single();
		jdbc.sql("update appointment set status='CANCELLED', updated_at=now() where id=:id")
				.param("id", firstId).update();
		credits.transition(userId, firstCredit, firstId, CreditEventType.RELEASED, "terminal-release-command-0001");

		mvc.perform(post("/api/v1/appointments").with(user(userId))
				.header("Idempotency-Key", "cap-request-after-terminal").contentType(MediaType.APPLICATION_JSON)
				.content(body(chatSlot(Instant.now().plusSeconds(97_200)), "IN_APP_CHAT")))
				.andExpect(status().isCreated());
	}

	@Test
	void inProgressAppointmentCountsTowardThePlusReservationCap() throws Exception {
		var userId = paidUser("PLUS");
		var inProgress = request(userId, chatSlot(Instant.now().plusSeconds(86_400)),
				"in-progress-first-0001", null);
		var inProgressId = UUID.fromString(json.readTree(inProgress.getResponse().getContentAsByteArray()).get("id").asText());
		jdbc.sql("""
				update appointment set status='IN_PROGRESS', decided_at=now(),
				decision_reason='SPECIALIST_ACCEPTED', updated_at=now() where id=:id
				""")
				.param("id", inProgressId).update();
		request(userId, chatSlot(Instant.now().plusSeconds(90_000)), "in-progress-second-001", null);

		mvc.perform(get("/api/v1/service-credits").with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.reservationCapacity.active").value(2))
				.andExpect(jsonPath("$.reservationCapacity.maximum").value(2))
				.andExpect(jsonPath("$.reservationCapacity.remaining").value(0));
		mvc.perform(post("/api/v1/appointments").with(user(userId))
				.header("Idempotency-Key", "in-progress-third-0001").contentType(MediaType.APPLICATION_JSON)
				.content(body(chatSlot(Instant.now().plusSeconds(93_600)), "IN_APP_CHAT")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("APPOINTMENT_RESERVATION_LIMIT_REACHED"));
	}

	@Test
	void rescheduleAtCapAtomicallyReusesTheHeldCreditAndPreservesTheOldSnapshot() throws Exception {
		var userId = paidUser("PLUS");
		var first = request(userId, chatSlot(Instant.now().plusSeconds(86_400)), "reschedule-first-00001", null);
		request(userId, chatSlot(Instant.now().plusSeconds(90_000)), "reschedule-second-0001", null);
		var firstJson = json.readTree(first.getResponse().getContentAsByteArray());
		var firstId = UUID.fromString(firstJson.get("id").asText());
		var firstCredit = firstJson.get("heldCreditId").asText();

		var replacement = request(userId, chatSlot(Instant.now().plusSeconds(93_600)),
				"reschedule-replace-001", firstId);
		var replacementJson = json.readTree(replacement.getResponse().getContentAsByteArray());

		assertThat(replacementJson.get("replacesAppointmentId").asText()).isEqualTo(firstId.toString());
		assertThat(replacementJson.get("heldCreditId").asText()).isEqualTo(firstCredit);
		assertThat(jdbc.sql("select status from appointment where id=:id").param("id", firstId)
				.query(String.class).single()).isEqualTo("CANCELLED");
		assertThat(jdbc.sql("""
				select count(*) from appointment
				where user_account_id=:userId and status in ('REQUESTED', 'CONFIRMED', 'IN_PROGRESS')
				""").param("userId", userId).query(Long.class).single()).isEqualTo(2L);
	}

	@Test
	void concurrentRequestsForOnePlusAccountCannotExceedReservationCap() throws Exception {
		var userId = paidUser("PLUS");
		request(userId, chatSlot(Instant.now().plusSeconds(86_400)), "race-cap-existing-0001", null);
		var slots = List.of(chatSlot(Instant.now().plusSeconds(90_000)), chatSlot(Instant.now().plusSeconds(93_600)));
		var ready = new CountDownLatch(2);
		var go = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var futures = slots.stream().map(slotId -> executor.submit(() -> {
				ready.countDown();
				go.await();
				return mvc.perform(post("/api/v1/appointments").with(user(userId))
						.header("Idempotency-Key", "race-cap-request-" + slotId)
						.contentType(MediaType.APPLICATION_JSON).content(body(slotId, "IN_APP_CHAT")))
						.andReturn().getResponse().getStatus();
			})).toList();
			ready.await();
			go.countDown();
			assertThat(futures.stream().map(future -> {
				try { return future.get(); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).toList()).containsExactlyInAnyOrder(201, 409);
		}
		assertThat(jdbc.sql("""
				select count(*) from appointment
				where user_account_id=:userId and status in ('REQUESTED', 'CONFIRMED', 'IN_PROGRESS')
				""").param("userId", userId).query(Long.class).single()).isEqualTo(2L);
	}

	@Test
	void concurrentSuspensionEitherRejectsOrCancelsRequestWithoutLeavingHeldCredit() throws Exception {
		var userId = paidUser("PLUS");
		var slotId = chatSlot(Instant.now().plusSeconds(172_800));
		var specialistId = jdbc.sql("select specialist_account_id from availability_slot where id=:id")
				.param("id", slotId).query(UUID.class).single();
		var ready = new CountDownLatch(2);
		var go = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var request = executor.submit(() -> {
				ready.countDown();
				go.await();
				return mvc.perform(post("/api/v1/appointments").with(user(userId))
						.header("Idempotency-Key", "suspension-race-request")
						.contentType(MediaType.APPLICATION_JSON).content(body(slotId, "IN_APP_CHAT")))
						.andReturn().getResponse().getStatus();
			});
			var suspension = executor.submit(() -> {
				ready.countDown();
				go.await();
				return mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/suspend", specialistId)
						.with(admin(UUID.randomUUID())).header("If-Match", "\"0\"")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reasonCode\":\"QUALITY_REVIEW_REQUIRED\"}"))
						.andReturn().getResponse().getStatus();
			});
			ready.await();
			go.countDown();
			assertThat(suspension.get()).isEqualTo(200);
			assertThat(request.get()).isIn(201, 409);
		}

		assertThat(jdbc.sql("select approval_status from specialist_profile where account_id=:id")
				.param("id", specialistId).query(String.class).single()).isEqualTo("SUSPENDED");
		assertThat(jdbc.sql("select count(*) from appointment where availability_slot_id=:id and status in ('REQUESTED','CONFIRMED')")
				.param("id", slotId).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("""
				select count(*) from service_credit credit
				join service_credit_period period on period.id=credit.period_id
				where period.account_id=:userId and credit.appointment_id is not null and credit.state='HELD'
				""").param("userId", userId).query(Long.class).single()).isZero();
	}

	private UUID paidUser(String packageCode) {
		var id = UUID.randomUUID();
		var now = Instant.now();
		jdbc.sql("""
				insert into current_service_entitlement (
				 account_id, package_code, source, source_reference, effective_from, effective_until, policy_version
				) values (:id, :package, 'PAID', :reference, :from, :until, 'service-entitlement-v1')
				""").param("id", id).param("package", packageCode).param("reference", "paid-" + id)
				.param("from", Timestamp.from(now.minusSeconds(60))).param("until", Timestamp.from(now.plusSeconds(2_592_000))).update();
		return id;
	}

	private UUID chatSlot(Instant start) {
		var specialist = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Online specialist', 'Appointment integration profile', 5, 'Asia/Ho_Chi_Minh',
				 'APPROVED', now(), now(), :admin)
				""").param("id", specialist).param("admin", UUID.randomUUID()).update();
		var slot = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (
				 id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at
				) values (:id, :specialist, :start, :end, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :key, now(), now())
				""").param("id", slot).param("specialist", specialist).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3_600))).param("key", "slot-key-" + slot).update();
		return slot;
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor admin(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private String body(UUID slotId, String modality) {
		return "{\"slotId\":\"%s\",\"modality\":\"%s\"}".formatted(slotId, modality);
	}

	private String body(UUID slotId, String modality, UUID replacesAppointmentId) {
		return "{\"slotId\":\"%s\",\"modality\":\"%s\",\"replacesAppointmentId\":\"%s\"}"
				.formatted(slotId, modality, replacesAppointmentId);
	}

	private MvcResult request(UUID userId, UUID slotId, String key, UUID replacesAppointmentId) throws Exception {
		return mvc.perform(post("/api/v1/appointments").with(user(userId))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(replacesAppointmentId == null ? body(slotId, "IN_APP_CHAT")
						: body(slotId, "IN_APP_CHAT", replacesAppointmentId)))
				.andExpect(status().isCreated()).andReturn();
	}
}
