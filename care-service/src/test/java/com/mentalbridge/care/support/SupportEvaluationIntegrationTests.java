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
import java.util.stream.StreamSupport;

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
		var outbox = jdbc.sql("""
				select message_type || '|' || schema_version || '|' || aggregate_type || '|' || aggregate_version
				from outbox_event where aggregate_id=:id
				""").param("id", id).query(String.class).single();
		var payload = objectMapper.readTree(jdbc.sql("""
				select payload::text from outbox_event where aggregate_id=:id
				""").param("id", id).query(String.class).single());
		assertThat(outbox).isEqualTo("care.support-tier.resolved|1.0|SUPPORT_EVALUATION|0");
		assertThat(payload.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
				"supportEvaluationId", "userId", "policyVersion", "supportTier", "reasonCodes",
				"phq9AssessmentId", "gad7AssessmentId", "evaluatedAt");
		assertThat(payload.has("answers")).isFalse();
		assertThat(payload.has("totalScore")).isFalse();
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
		var aliasKey = "support-professional-alias-1";
		mvc.perform(post("/api/v1/support-evaluations").with(user(userId)).header("Idempotency-Key", aliasKey)
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.supportEvaluationId").value(id));
		var otherGad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "MILD", false);
		mvc.perform(post("/api/v1/support-evaluations").with(user(userId)).header("Idempotency-Key", aliasKey)
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, otherGad7)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		assertThat(jdbc.sql("select count(*) from support_evaluation where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_evaluation_request where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(2);
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
				.content(body(phq9, foreignGad7))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ASSESSMENT_NOT_FOUND"));
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
	void rejectsMissingIncompleteAndConflictingReplayAndKeepsReadsOwnerScoped() throws Exception {
		var userId = insertProfile();
		var otherUser = insertProfile();
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "MILD", false);
		var otherGad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "MODERATE", false);
		var incompleteGad7 = insertIncompleteAssessment(userId, GAD7_DEFINITION);

		mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-missing-000001").contentType(MediaType.APPLICATION_JSON)
				.content(body(phq9, UUID.randomUUID()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ASSESSMENT_NOT_FOUND"));
		mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-incomplete-0001").contentType(MediaType.APPLICATION_JSON)
				.content(body(phq9, incompleteGad7))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUPPORT_EVIDENCE_INCOMPATIBLE"));

		var key = "support-replay-conflict-01";
		var created = mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(body(phq9, gad7))).andExpect(status().isCreated()).andReturn();
		var evaluationId = objectMapper.readTree(created.getResponse().getContentAsString())
				.get("supportEvaluationId").asText();
		mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(body(phq9, otherGad7))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		mvc.perform(get("/api/v1/support-evaluations/{id}", evaluationId).with(user(otherUser)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUPPORT_EVALUATION_NOT_FOUND"));
	}

	@Test
	void serializesEveryVersionedMeaningAndEveryTierNextStep() throws Exception {
		var userId = insertProfile();
		var phqLevels = new String[] { "MINIMAL", "MILD", "MODERATE", "MODERATELY_SEVERE", "SEVERE" };
		var gadLevels = new String[] { "MINIMAL", "MILD", "MODERATE", "SEVERE", "MINIMAL" };
		var meaningCodes = new java.util.HashSet<String>();
		var tiers = new java.util.HashSet<String>();
		var nextSteps = new java.util.HashSet<String>();

		for (var index = 0; index < phqLevels.length; index++) {
			var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", phqLevels[index], index == 4);
			var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", gadLevels[index], false);
			var response = mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
					.header("Idempotency-Key", "support-meaning-" + index + "-000000")
					.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.evidence[0].meaning.referencePeriodDays").value(14))
					.andExpect(jsonPath("$.evidence[1].meaning.referencePeriodDays").value(14))
					.andExpect(jsonPath("$.evidence[0].meaning.limitation").isNotEmpty())
					.andExpect(jsonPath("$.evidence[1].meaning.limitation").isNotEmpty())
					.andExpect(jsonPath("$.nextStep.text").isNotEmpty())
					.andExpect(jsonPath("$.nextStep.boundary").isNotEmpty())
					.andReturn();
			var json = objectMapper.readTree(response.getResponse().getContentAsString());
			tiers.add(json.get("supportTier").asText());
			nextSteps.add(json.at("/nextStep/code").asText());
			StreamSupport.stream(json.get("evidence").spliterator(), false)
					.map(node -> node.at("/meaning/meaningCode").asText()).forEach(meaningCodes::add);
		}

		assertThat(meaningCodes).containsExactlyInAnyOrder(
				"PHQ9_MINIMAL_14D", "PHQ9_MILD_14D", "PHQ9_MODERATE_14D",
				"PHQ9_MODERATELY_SEVERE_14D", "PHQ9_SEVERE_14D",
				"GAD7_MINIMAL_14D", "GAD7_MILD_14D", "GAD7_MODERATE_14D", "GAD7_SEVERE_14D");
		assertThat(tiers).containsExactlyInAnyOrder("SELF_GUIDED_SUPPORT", "PROFESSIONAL_SUPPORT_RECOMMENDED",
				"SAFETY_FOLLOW_UP_RECOMMENDED");
		assertThat(nextSteps).containsExactlyInAnyOrder("REVIEW_SELF_GUIDED_RESOURCE",
				"CONSIDER_PROFESSIONAL_SUPPORT", "REVIEW_SAFETY_GUIDANCE");
	}

	@Test
	void serializesConcurrentEvaluationOfTheSameEvidencePair() throws Exception {
		var userId = insertProfile();
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "MILD", false);
		var command = new SupportEvaluationService.EvaluationCommand(phq9, gad7);
		var gate = new CountDownLatch(1);
		UUID evaluationId;
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> { gate.await(); return service.evaluate(userId, "support-race-first-0001", UUID.randomUUID(), command); });
			var second = executor.submit(() -> { gate.await(); return service.evaluate(userId, "support-race-second-001", UUID.randomUUID(), command); });
			gate.countDown();
			var firstResult = first.get();
			var secondResult = second.get();
			assertThat(firstResult.supportTier()).isEqualTo(SupportTier.SELF_GUIDED_SUPPORT);
			assertThat(firstResult.supportEvaluationId()).isEqualTo(secondResult.supportEvaluationId());
			evaluationId = firstResult.supportEvaluationId();
		}
		assertThat(jdbc.sql("select count(*) from support_evaluation where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_evaluation_request where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id=:id")
				.param("id", evaluationId).query(Long.class).single()).isEqualTo(1);
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
				""").param("id", id).param("score", scoreFor(level))
				.param("level", level).param("scoringVersion", gad7 ? "gad7-standard-bands-v1" : "phq9-standard-bands-v1")
				.param("positive", gad7 ? null : positive)
				.param("safetyStatus", gad7 ? "NOT_APPLICABLE" : positive ? "POSITIVE_SAFETY_SCREEN" : "NEGATIVE_SAFETY_SCREEN")
				.param("safetyPolicy", gad7 ? null : "MB-SAFETY-PHQ9-001-v1").update();
		return id;
	}

	private UUID insertIncompleteAssessment(UUID userId, UUID definitionId) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into assessment_submission
				(id,user_id,definition_id,idempotency_key,request_hash,privacy_policy_version,submitted_at)
				values (:id,:userId,:definitionId,:key,:hash,'privacy-capstone-v3',now())
				""").param("id", id).param("userId", userId).param("definitionId", definitionId)
				.param("key", "assessment-" + UUID.randomUUID()).param("hash", "0".repeat(64)).update();
		return id;
	}

	private int scoreFor(String level) {
		return switch (level) {
			case "MINIMAL" -> 0;
			case "MILD" -> 5;
			case "MODERATE" -> 10;
			case "MODERATELY_SEVERE" -> 15;
			case "SEVERE" -> 20;
			default -> throw new IllegalArgumentException("Unsupported test screening level");
		};
	}

	private String body(UUID phq9, UUID gad7) {
		return "{\"phq9AssessmentId\":\"" + phq9 + "\",\"gad7AssessmentId\":\"" + gad7 + "\"}";
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
