package com.mentalbridge.consultation.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AvailabilityFlowIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper objectMapper;

	@Test
	void approvedSpecialistPublishesListsReplaysAndWithdrawsChatAvailability() throws Exception {
		var specialistId = approvedSpecialist();
		var start = Instant.now().plusSeconds(86_400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		var first = mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "publish-chat-slot-0001")
				.contentType(MediaType.APPLICATION_JSON).content(slotBody(start, "IN_APP_CHAT")))
				.andExpect(status().isCreated()).andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.readiness").value("AVAILABLE"))
				.andExpect(jsonPath("$.modality").value("IN_APP_CHAT")).andReturn();
		var original = json(first);

		mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "publish-chat-slot-0001")
				.contentType(MediaType.APPLICATION_JSON).content(slotBody(start, "IN_APP_CHAT")))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(original.get("id").asText()));

		mvc.perform(get("/api/v1/availability-slots").with(specialist(specialistId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.videoPublishingEnabled").value(false))
				.andExpect(jsonPath("$.items[0].id").value(original.get("id").asText()));

		var withdrawn = mvc.perform(delete("/api/v1/availability-slots/{id}", original.get("id").asText())
				.with(specialist(specialistId)).header("If-Match", first.getResponse().getHeader("ETag")))
				.andExpect(status().isOk()).andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.status").value("WITHDRAWN"))
				.andExpect(jsonPath("$.readiness").value("WITHDRAWN")).andReturn();

		mvc.perform(delete("/api/v1/availability-slots/{id}", original.get("id").asText())
				.with(specialist(specialistId)).header("If-Match", withdrawn.getResponse().getHeader("ETag")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AVAILABILITY_SLOT_WITHDRAWN"));
	}

	@Test
	void validationApprovalOwnershipAndVideoGateFailClosed() throws Exception {
		var specialistId = approvedSpecialist();
		var otherSpecialistId = approvedSpecialist();
		var pendingSpecialistId = pendingSpecialist();
		var start = Instant.now().plusSeconds(172_800).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

		mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "invalid-duration-key-01").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start, start.plusSeconds(2_700), "IN_APP_CHAT")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].code").value("INVALID_SLOT_DURATION"));
		mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "invalid-timezone-key-01").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start.plusSeconds(3_600), "IN_APP_CHAT").replace("Asia/Ho_Chi_Minh", "Mars/Olympus")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].code").value("INVALID_TIMEZONE"));
		mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "non-utc-instant-key-01").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start.plusSeconds(10_800), "IN_APP_CHAT").replace("Z\"", "+07:00\"")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "disabled-video-key-01").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start, "IN_APP_VIDEO")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIDEO_AVAILABILITY_DISABLED"));
		mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "unsupported-mode-key-01").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start, "IN_PERSON"))).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/availability-slots").with(specialist(pendingSpecialistId))
				.header("Idempotency-Key", "pending-profile-key-01").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start, "IN_APP_CHAT")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SPECIALIST_NOT_APPROVED"));
		mvc.perform(post("/api/v1/availability-slots").with(user(UUID.randomUUID()))
				.header("Idempotency-Key", "wrong-role-publish-01").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start, "IN_APP_CHAT"))).andExpect(status().isForbidden());

		var created = mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "owned-withdraw-key-001").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start.plusSeconds(7_200), "IN_APP_CHAT"))).andExpect(status().isCreated()).andReturn();
		var id = json(created).get("id").asText();
		mvc.perform(delete("/api/v1/availability-slots/{id}", id).with(specialist(otherSpecialistId))
				.header("If-Match", created.getResponse().getHeader("ETag")))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AVAILABILITY_SLOT_NOT_FOUND"));
		mvc.perform(delete("/api/v1/availability-slots/{id}", id).with(specialist(specialistId))
				.header("If-Match", "\"99\""))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("AVAILABILITY_SLOT_VERSION_MISMATCH"));

		var staleId = UUID.randomUUID();
		var staleStart = Instant.now().minusSeconds(7_200);
		jdbc.sql("""
				insert into availability_slot (
				id, specialist_account_id, start_at, end_at, timezone, modality,
				idempotency_key, created_at, updated_at
				) values (:id, :specialist, :start, :end, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT',
				'stale-withdraw-key-0001', :created, :created)
				""").param("id", staleId).param("specialist", specialistId).param("start", staleStart)
				.param("end", staleStart.plusSeconds(3_600)).param("created", staleStart.minusSeconds(3_600)).update();
		mvc.perform(delete("/api/v1/availability-slots/{id}", staleId).with(specialist(specialistId))
				.header("If-Match", "\"0\""))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AVAILABILITY_SLOT_STALE"));
	}

	@Test
	void overlapAndConflictingIdempotencyAreRejected() throws Exception {
		var specialistId = approvedSpecialist();
		var start = Instant.now().plusSeconds(259_200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "overlap-original-key-01").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start, "IN_APP_CHAT"))).andExpect(status().isCreated());
		mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "overlap-second-key-001").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start.plusSeconds(1_800), "IN_APP_CHAT")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AVAILABILITY_SLOT_OVERLAP"));
		mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "overlap-original-key-01").contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start.plusSeconds(7_200), "IN_APP_CHAT")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void concurrentOverlappingPublicationHasOneWinner() throws Exception {
		var specialistId = approvedSpecialist();
		var start = Instant.now().plusSeconds(345_600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		var ready = new CountDownLatch(2);
		var go = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var results = List.of("concurrent-publish-key-01", "concurrent-publish-key-02").stream()
					.map(key -> executor.submit(() -> {
						ready.countDown();
						go.await();
						return mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
								.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
								.content(slotBody(start, "IN_APP_CHAT"))).andReturn().getResponse().getStatus();
					})).toList();
			ready.await();
			go.countDown();
			var statuses = results.stream().map(result -> {
				try {
					return result.get();
				}
				catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
			}).toList();
			assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		}
	}

	private UUID approvedSpecialist() {
		var id = pendingSpecialist();
		jdbc.sql("""
				update specialist_profile set approval_status='APPROVED', submitted_at=now(),
				reviewed_at=now(), reviewed_by=:admin where account_id=:id
				""").param("admin", UUID.randomUUID()).param("id", id).update();
		return id;
	}

	private UUID pendingSpecialist() {
		return jdbc.sql("""
				insert into specialist_profile (account_id, display_name, biography, years_experience, timezone)
				values (:id, 'Availability specialist', 'Synthetic integration profile', 3, 'Asia/Ho_Chi_Minh')
				returning account_id
				""").param("id", UUID.randomUUID()).query(UUID.class).single();
	}

	private RequestPostProcessor specialist(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"));
	}

	private RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private String slotBody(Instant start, String modality) {
		return slotBody(start, start.plusSeconds(3_600), modality);
	}

	private String slotBody(Instant start, Instant end, String modality) {
		return """
				{"startAt":"%s","endAt":"%s","timezone":"Asia/Ho_Chi_Minh","modality":"%s"}
				""".formatted(start, end, modality);
	}

	private JsonNode json(org.springframework.test.web.servlet.MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}
}
