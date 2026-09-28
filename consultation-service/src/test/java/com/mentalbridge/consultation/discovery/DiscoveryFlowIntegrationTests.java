package com.mentalbridge.consultation.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.specialist.SupportArea;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DiscoveryFlowIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@MockitoBean ScreeningContextResolver screeningContexts;

	@Test
	void returnsOnlyApprovedProfilesAndCurrentSelectableChatSlots() throws Exception {
		var approved = profile("APPROVED", "Approved specialist", "Asia/Ho_Chi_Minh",
				SupportArea.ANXIETY_SYMPTOMS, "vi");
		var selectable = slot(approved, Instant.now().plusSeconds(86_400), "IN_APP_CHAT", "ACTIVE");
		slot(approved, Instant.now().plusSeconds(3_600), "IN_APP_CHAT", "ACTIVE");
		slot(approved, Instant.now().plusSeconds(90_000), "IN_APP_CHAT", "WITHDRAWN");
		slot(approved, Instant.now().plusSeconds(93_600), "IN_APP_VIDEO", "ACTIVE");
		profile("PENDING", "Pending specialist", "Asia/Ho_Chi_Minh", SupportArea.ANXIETY_SYMPTOMS, "vi");
		profile("REJECTED", "Rejected specialist", "Asia/Ho_Chi_Minh", SupportArea.ANXIETY_SYMPTOMS, "vi");
		profile("SUSPENDED", "Suspended specialist", "Asia/Ho_Chi_Minh", SupportArea.ANXIETY_SYMPTOMS, "vi");

		var response = discover(UUID.randomUUID());

		response.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].specialistAccountId").value(approved.toString()))
				.andExpect(jsonPath("$.items[0].selectableSlots.length()").value(1))
				.andExpect(jsonPath("$.items[0].selectableSlots[0].id").value(selectable.toString()))
				.andExpect(jsonPath("$.items[0].selectableSlots[0].modality").value("IN_APP_CHAT"))
				.andExpect(jsonPath("$.videoEnabled").value(false));
	}

	@Test
	void suspensionHidesImmediatelyAndRestorationDoesNotReviveWithdrawnSlots() throws Exception {
		var specialist = profile("APPROVED", "Lifecycle specialist", "Asia/Ho_Chi_Minh",
				SupportArea.DEPRESSIVE_SYMPTOMS, "en");
		var originalSlot = slot(specialist, Instant.now().plusSeconds(86_400), "IN_APP_CHAT", "ACTIVE");
		var admin = UUID.randomUUID();

		discover(UUID.randomUUID()).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].specialistAccountId").value(specialist.toString()));
		var suspended = mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/suspend", specialist)
				.with(admin(admin)).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"QUALITY_REVIEW_REQUIRED\"}"))
				.andExpect(status().isOk()).andReturn();

		discover(UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
		mvc.perform(get("/api/v1/specialists/{id}", specialist).with(user(UUID.randomUUID())))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SPECIALIST_NOT_DISCOVERABLE"));

		var restored = mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/restore", specialist)
				.with(admin(admin)).header("If-Match", suspended.getResponse().getHeader("ETag")))
				.andExpect(status().isOk()).andReturn();
		assertThat(restored.getResponse().getHeader("ETag")).isNotBlank();
		discover(UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].selectableSlots").isEmpty());
		assertThat(jdbc.sql("select status from availability_slot where id=:id").param("id", originalSlot)
				.query(String.class).single()).isEqualTo("WITHDRAWN");

		var replacement = slot(specialist, Instant.now().plusSeconds(172_800), "IN_APP_CHAT", "ACTIVE");
		discover(UUID.randomUUID()).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].selectableSlots[0].id").value(replacement.toString()));
	}

	@Test
	void freeCanBrowseWhilePaidPlansReceiveOnlyTheBookingPolicyHandoff() throws Exception {
		profile("APPROVED", "Entitlement specialist", "Asia/Ho_Chi_Minh",
				SupportArea.ANXIETY_SYMPTOMS, "vi");

		discover(UUID.randomUUID()).andExpect(status().isOk())
				.andExpect(jsonPath("$.packageCode").value("FREE"))
				.andExpect(jsonPath("$.bookingHandoff").value("BROWSE_ONLY"))
				.andExpect(jsonPath("$.items[0].explanation.ratingTieBreakerApplied").value(false));
		discover(paidUser("PLUS")).andExpect(status().isOk())
				.andExpect(jsonPath("$.packageCode").value("PLUS"))
				.andExpect(jsonPath("$.bookingHandoff").value("BOOKING_POLICY_CHECK_REQUIRED"));
		discover(paidUser("PREMIUM")).andExpect(status().isOk())
				.andExpect(jsonPath("$.packageCode").value("PREMIUM"))
				.andExpect(jsonPath("$.bookingHandoff").value("BOOKING_POLICY_CHECK_REQUIRED"))
				.andExpect(jsonPath("$.items[0].explanation.ratingTieBreakerApplied").value(false));
	}

	@Test
	void primaryCompatibilityWinsAndUuidBreaksExactTiesDeterministically() throws Exception {
		var evaluation = UUID.randomUUID();
		when(screeningContexts.resolve(evaluation, "token")).thenReturn(java.util.Optional.of(
				new ScreeningContextResolver.ScreeningContext("screening-policy-v2", Map.of(
						SupportArea.ANXIETY_SYMPTOMS, 2, SupportArea.DEPRESSIVE_SYMPTOMS, 1))));
		var compatible = profile("APPROVED", "Primary match", "UTC", SupportArea.ANXIETY_SYMPTOMS, "en");
		var secondary = profile("APPROVED", "Secondary match", "Asia/Ho_Chi_Minh",
				SupportArea.DEPRESSIVE_SYMPTOMS, "vi");
		slot(secondary, Instant.now().plusSeconds(86_400), "IN_APP_CHAT", "ACTIVE");

		mvc.perform(get("/api/v1/specialists").with(user(UUID.randomUUID()))
				.param("supportEvaluationId", evaluation.toString()).param("language", "vi")
				.param("timezone", "Asia/Ho_Chi_Minh"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.contextState").value("APPLIED"))
				.andExpect(jsonPath("$.items[0].specialistAccountId").value(compatible.toString()))
				.andExpect(jsonPath("$.items[0].explanation.compatibility").value("MATCHED"))
				.andExpect(jsonPath("$.items[1].specialistAccountId").value(secondary.toString()));

		jdbc.sql("delete from availability_slot where specialist_account_id in (:first,:second)")
				.param("first", compatible).param("second", secondary).update();
		jdbc.sql("delete from specialist_profile where account_id in (:first,:second)")
				.param("first", compatible).param("second", secondary).update();
		var firstTie = profile("APPROVED", "Tie A", "UTC", SupportArea.ANXIETY_SYMPTOMS, "en");
		var secondTie = profile("APPROVED", "Tie B", "UTC", SupportArea.ANXIETY_SYMPTOMS, "en");
		var expectedFirst = firstTie.compareTo(secondTie) < 0 ? firstTie : secondTie;
		var expectedSecond = firstTie.equals(expectedFirst) ? secondTie : firstTie;

		var firstPage = mvc.perform(get("/api/v1/specialists").with(user(UUID.randomUUID())).param("limit", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].specialistAccountId")
						.value(expectedFirst.toString())).andExpect(jsonPath("$.nextCursor").isNotEmpty()).andReturn();
		var cursor = json.readTree(firstPage.getResponse().getContentAsByteArray()).get("nextCursor").asText();
		mvc.perform(get("/api/v1/specialists").with(user(UUID.randomUUID())).param("limit", "1")
				.param("cursor", cursor)).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].specialistAccountId").value(expectedSecond.toString()))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void heldSlotAndStaleCursorFailClosedWhileCareFailureFallsBackToNeutral() throws Exception {
		var specialist = profile("APPROVED", "Stale slot specialist", "Asia/Ho_Chi_Minh",
				SupportArea.ANXIETY_SYMPTOMS, "vi");
		var heldSlot = slot(specialist, Instant.now().plusSeconds(86_400), "IN_APP_CHAT", "ACTIVE");
		var paid = paidUser("PLUS");
		mvc.perform(post("/api/v1/appointments").with(user(paid))
				.header("Idempotency-Key", "discovery-held-slot-request")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"slotId\":\"%s\",\"modality\":\"IN_APP_CHAT\"}".formatted(heldSlot)))
				.andExpect(status().isCreated());

		var evaluation = UUID.randomUUID();
		when(screeningContexts.resolve(evaluation, "token")).thenReturn(java.util.Optional.empty());
		mvc.perform(get("/api/v1/specialists").with(user(UUID.randomUUID()))
				.param("supportEvaluationId", evaluation.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.contextState").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.items[0].selectableSlots").isEmpty())
				.andExpect(jsonPath("$.items[0].explanation.compatibility").value("UNAVAILABLE"));
		mvc.perform(get("/api/v1/specialists").with(user(UUID.randomUUID())).param("cursor", "not-a-cursor"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DISCOVERY_CURSOR_STALE"));
	}

	@Test
	void emptyAndInvalidRequestsReturnStableNonSensitiveResponses() throws Exception {
		discover(UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.count").value(0));
		mvc.perform(get("/api/v1/specialists").with(user(UUID.randomUUID())).param("language", "fr"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(get("/api/v1/specialists").with(user(UUID.randomUUID())).param("timezone", "Mars/Olympus"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		var body = discover(UUID.randomUUID()).andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("journal", "assessmentAnswer", "chatContent", "phone", "price",
				"practiceLocation", "externalMeeting", "credential", "specialty");
	}

	private org.springframework.test.web.servlet.ResultActions discover(UUID userId) throws Exception {
		return mvc.perform(get("/api/v1/specialists").with(user(userId)));
	}

	private UUID profile(String status, String name, String timezone, SupportArea area, String language) {
		var specialist = UUID.randomUUID();
		var reviewed = !"PENDING".equals(status);
		var reason = switch (status) {
			case "REJECTED" -> "OUTSIDE_SUPPORTED_SCOPE";
			case "SUSPENDED" -> "QUALITY_REVIEW_REQUIRED";
			default -> null;
		};
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by, decision_reason_code
				) values (:id, :name, 'Synthetic public biography', 5, :timezone, :status,
				 :submitted, :reviewed, :reviewer, :reason)
				""").param("id", specialist).param("name", name).param("timezone", timezone).param("status", status)
				.param("submitted", Timestamp.from(Instant.now().minusSeconds(3_600)))
				.param("reviewed", reviewed ? Timestamp.from(Instant.now().minusSeconds(1_800)) : null)
				.param("reviewer", reviewed ? UUID.randomUUID() : null).param("reason", reason).update();
		jdbc.sql("insert into specialist_profile_support_area values (:id, :area)")
				.param("id", specialist).param("area", area.name()).update();
		jdbc.sql("insert into specialist_profile_language values (:id, :language)")
				.param("id", specialist).param("language", language).update();
		return specialist;
	}

	private UUID slot(UUID specialist, Instant start, String modality, String status) {
		var slot = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (
				 id, specialist_account_id, start_at, end_at, timezone, modality, status,
				 idempotency_key, withdrawn_at, created_at, updated_at
				) values (:id, :specialist, :start, :end, 'Asia/Ho_Chi_Minh', :modality, :status,
				 :key, :withdrawn, now(), now())
				""").param("id", slot).param("specialist", specialist).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3_600))).param("modality", modality)
				.param("status", status).param("key", "discovery-slot-" + slot)
				.param("withdrawn", "WITHDRAWN".equals(status) ? Timestamp.from(Instant.now()) : null).update();
		return slot;
	}

	private UUID paidUser(String packageCode) {
		var id = UUID.randomUUID();
		var now = Instant.now();
		jdbc.sql("""
				insert into current_service_entitlement (
				 account_id, package_code, source, source_reference, effective_from, effective_until, policy_version
				) values (:id, :package, 'PAID', :reference, :from, :until, 'service-entitlement-v1')
				""").param("id", id).param("package", packageCode).param("reference", "discovery-paid-" + id)
				.param("from", Timestamp.from(now.minusSeconds(60)))
				.param("until", Timestamp.from(now.plusSeconds(2_592_000))).update();
		return id;
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).tokenValue("token"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor admin(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).tokenValue("admin-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}
}
