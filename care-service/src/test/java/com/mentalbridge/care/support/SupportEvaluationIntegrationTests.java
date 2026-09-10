package com.mentalbridge.care.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SupportEvaluationIntegrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID INCOMPATIBLE_PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper objectMapper;
	@Autowired SupportEvaluationService service;

	@Test
	void requiresAnAuthenticatedUserRole() throws Exception {
		var request = post("/api/v1/support-evaluations").header("Idempotency-Key", "support-auth-000000001")
				.contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID()));
		mvc.perform(request).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/support-evaluations")
				.with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST")))
				.header("Idempotency-Key", "support-auth-000000002")
				.contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID())))
				.andExpect(status().isForbidden());
	}

	@Test
	void safetyWinsAndReturnsApprovedLocalFallbackWhenOptionalProvidersAreUnavailable() throws Exception {
		var userId = insertProfile();
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", "SEVERE", true);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "SEVERE", false);

		var response = mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-safety-000001")
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.supportTier").value("SAFETY_FOLLOW_UP_RECOMMENDED"))
				.andExpect(jsonPath("$.reasonCodes[0]").value("PHQ9_SAFETY_SCREEN_POSITIVE"))
				.andExpect(jsonPath("$.evidence.length()").value(2))
				.andExpect(jsonPath("$.evidence[0].meaning.referencePeriodDays").value(14))
				.andExpect(jsonPath("$.safetyGuidance").value(SupportEvaluationService.SAFETY_FALLBACK))
				.andExpect(jsonPath("$.disclaimer").value(SupportEvaluationService.DISCLAIMER_TEXT))
				.andExpect(jsonPath("$.totalScore").doesNotExist())
				.andReturn();

		var id = UUID.fromString(objectMapper.readTree(response.getResponse().getContentAsString())
				.get("supportEvaluationId").asText());
		mvc.perform(get("/api/v1/support-evaluations/{id}", id).with(user(userId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.supportEvaluationId").value(id.toString()));
		assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id=:id")
				.param("id", id).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void professionalReasonsAreStableAndRetryIsIdempotent() throws Exception {
		var userId = insertProfile();
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", "MODERATE", false);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "SEVERE", false);
		var key = "support-professional-0001";
		var first = mvc.perform(post("/api/v1/support-evaluations").with(user(userId)).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.supportTier").value("PROFESSIONAL_SUPPORT_RECOMMENDED"))
				.andExpect(jsonPath("$.reasonCodes[0]").value("PHQ9_MODERATE_OR_HIGHER"))
				.andExpect(jsonPath("$.reasonCodes[1]").value("GAD7_MODERATE_OR_HIGHER")).andReturn();
		var id = objectMapper.readTree(first.getResponse().getContentAsString()).get("supportEvaluationId").asText();

		mvc.perform(post("/api/v1/support-evaluations").with(user(userId)).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.supportEvaluationId").value(id));
		assertThat(jdbc.sql("select count(*) from support_evaluation where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void rejectsDuplicateForeignVoidedAndWrongInstrumentEvidence() throws Exception {
		var userId = insertProfile();
		var otherUser = insertProfile();
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "MILD", false);
		var foreignGad7 = insertAssessment(otherUser, GAD7_DEFINITION, "GAD7", "MILD", false);
		var incompatiblePhq9 = insertAssessment(userId, INCOMPATIBLE_PHQ9_DEFINITION, "PHQ9", "MILD", false);

		mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-duplicate-00001").contentType(MediaType.APPLICATION_JSON)
				.content(body(phq9, phq9))).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-foreign-0000001").contentType(MediaType.APPLICATION_JSON)
				.content(body(phq9, foreignGad7))).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-wrong-00000001").contentType(MediaType.APPLICATION_JSON)
				.content(body(gad7, phq9))).andExpect(status().isConflict());
		mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-version-0000001").contentType(MediaType.APPLICATION_JSON)
				.content(body(incompatiblePhq9, gad7))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUPPORT_EVIDENCE_INCOMPATIBLE"));
		jdbc.sql("update assessment_submission set voided_at=now(),void_reason_code='TEST_VOID' where id=:id")
				.param("id", gad7).update();
		mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-voided-0000001").contentType(MediaType.APPLICATION_JSON)
				.content(body(phq9, gad7))).andExpect(status().isConflict());
	}

	@Test
	void serializesConcurrentEvaluationOfTheSameEvidencePair() throws Exception {
		var userId = insertProfile();
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "MILD", false);
		var command = new SupportEvaluationService.EvaluationCommand(phq9, gad7);
		var gate = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> { gate.await(); return service.evaluate(userId, "support-race-first-0001", UUID.randomUUID(), command); });
			var second = executor.submit(() -> { gate.await(); return service.evaluate(userId, "support-race-second-001", UUID.randomUUID(), command); });
			gate.countDown();
			var firstResult = first.get();
			var secondResult = second.get();
			assertThat(firstResult.supportTier()).isEqualTo(SupportTier.SELF_GUIDED_SUPPORT);
			assertThat(firstResult.supportEvaluationId()).isEqualTo(secondResult.supportEvaluationId());
		}
		assertThat(jdbc.sql("select count(*) from support_evaluation where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(1);
	}

	private UUID insertProfile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id,display_name) values (:id,'Support test user')")
				.param("id", userId).update();
		return userId;
	}

	private UUID insertAssessment(UUID userId, UUID definitionId, String instrument, String level, boolean positive) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into assessment_submission
				(id,user_id,definition_id,idempotency_key,request_hash,privacy_policy_version,submitted_at)
				values (:id,:userId,:definitionId,:key,:hash,'privacy-capstone-v3',now())
				""").param("id", id).param("userId", userId).param("definitionId", definitionId)
				.param("key", "assessment-" + UUID.randomUUID()).param("hash", "0".repeat(64)).update();
		var gad7 = instrument.equals("GAD7");
		jdbc.sql("""
				insert into assessment_result
				(submission_id,total_score,screening_level,scoring_version,safety_item_positive,
				 safety_status,safety_policy_version,disclaimer_code,calculated_at)
				values (:id,:score,:level,:scoringVersion,:positive,:safetyStatus,:safetyPolicy,
				'SCREENING_NOT_DIAGNOSIS',now())
				""").param("id", id).param("score", level.equals("SEVERE") ? 20 : level.equals("MODERATE") ? 10 : 5)
				.param("level", level).param("scoringVersion", gad7 ? "gad7-standard-bands-v1" : "phq9-standard-bands-v1")
				.param("positive", gad7 ? null : positive)
				.param("safetyStatus", gad7 ? "NOT_APPLICABLE" : positive ? "POSITIVE_SAFETY_SCREEN" : "NEGATIVE_SAFETY_SCREEN")
				.param("safetyPolicy", gad7 ? null : "MB-SAFETY-PHQ9-001-v1").update();
		return id;
	}

	private String body(UUID phq9, UUID gad7) {
		return "{\"phq9AssessmentId\":\"" + phq9 + "\",\"gad7AssessmentId\":\"" + gad7 + "\"}";
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
