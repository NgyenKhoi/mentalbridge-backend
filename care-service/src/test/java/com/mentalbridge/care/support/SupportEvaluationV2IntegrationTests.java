package com.mentalbridge.care.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class SupportEvaluationV2IntegrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID INCOMPATIBLE_PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper objectMapper;
	@Autowired SupportEvaluationV2Service service;

	@Test
	void requiresUserAuthenticationAndKeepsOwnerReadsOpaque() throws Exception {
		mvc.perform(post("/api/v2/support-evaluations").header("Idempotency-Key", "support-v2-auth-00001")
				.contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID())))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v2/support-evaluations")
				.with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST")))
				.header("Idempotency-Key", "support-v2-auth-00002")
				.contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID())))
				.andExpect(status().isForbidden());

		var owner = insertProfile();
		var other = insertProfile();
		var evaluationId = create(owner, insertAssessment(owner, PHQ9_DEFINITION, "PHQ9", "MILD", false),
				insertAssessment(owner, GAD7_DEFINITION, "GAD7", "MILD", false), "support-v2-owner-0001");
		mvc.perform(get("/api/v2/support-evaluations/{id}", evaluationId).with(user(other)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUPPORT_EVALUATION_NOT_FOUND"));
	}

	@Test
	void persistsExactDomainSnapshotsAndIndependentPositiveSafetyWithOneMinimizedEvent() throws Exception {
		var userId = insertProfile();
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", "MILD", true);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "MODERATE", false);

		var response = mvc.perform(post("/api/v2/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-v2-domains-0001")
				.header("X-Correlation-Id", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.evaluationVersion").value(2))
				.andExpect(jsonPath("$.policyVersion").value("mb-support-routing-capstone-v2"))
				.andExpect(jsonPath("$.contributingDomains.length()").value(2))
				.andExpect(jsonPath("$.contributingDomains[0].instrument").value("PHQ9"))
				.andExpect(jsonPath("$.contributingDomains[0].domain").value("DEPRESSIVE_SYMPTOMS"))
				.andExpect(jsonPath("$.contributingDomains[0].screeningLevel").value("MILD"))
				.andExpect(jsonPath("$.contributingDomains[0].supportPathway").value("SELF_GUIDED_SUPPORT"))
				.andExpect(jsonPath("$.contributingDomains[0].reasonCodes[0]").value("PHQ9_LEVEL_MILD"))
				.andExpect(jsonPath("$.contributingDomains[1].domain").value("ANXIETY_SYMPTOMS"))
				.andExpect(jsonPath("$.contributingDomains[1].screeningLevel").value("MODERATE"))
				.andExpect(jsonPath("$.contributingDomains[1].supportPathway")
						.value("PROFESSIONAL_SUPPORT_RECOMMENDED"))
				.andExpect(jsonPath("$.safetyEvidence.sourceAssessmentId").value(phq9.toString()))
				.andExpect(jsonPath("$.safetyEvidence.status").value("POSITIVE_SAFETY_SCREEN"))
				.andExpect(jsonPath("$.safetyEvidence.reasonCode").value("PHQ9_ITEM9_POSITIVE"))
				.andExpect(jsonPath("$.supportTier").doesNotExist())
				.andExpect(jsonPath("$.overallSeverity").doesNotExist())
				.andReturn();

		var evaluationId = UUID.fromString(objectMapper.readTree(response.getResponse().getContentAsString())
				.get("supportEvaluationId").asText());
		assertThat(jdbc.sql("select count(*) from support_evaluation_v2_domain where support_evaluation_id=:id")
				.param("id", evaluationId).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from support_evaluation_v2_safety where support_evaluation_id=:id")
				.param("id", evaluationId).query(Long.class).single()).isEqualTo(1);
		var outbox = jdbc.sql("""
				select message_type || '|' || schema_version || '|' || aggregate_type || '|' || aggregate_version
				from outbox_event where aggregate_id=:id
				""").param("id", evaluationId).query(String.class).single();
		var payload = objectMapper.readTree(jdbc.sql("select payload::text from outbox_event where aggregate_id=:id")
				.param("id", evaluationId).query(String.class).single());
		assertThat(outbox).isEqualTo("care.support-evaluation.created|2.0|SUPPORT_EVALUATION_V2|0");
		assertThat(payload.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
				"supportEvaluationId", "userId", "evaluationVersion", "policyVersion", "evaluatedAt",
				"contributingDomains", "safetyEvidence");
		assertThat(payload.at("/contributingDomains/0/questionnaireDefinitionId").asText())
				.isEqualTo(PHQ9_DEFINITION.toString());
		assertThat(payload.at("/contributingDomains/1/questionnaireDefinitionId").asText())
				.isEqualTo(GAD7_DEFINITION.toString());
		assertThat(payload.toString()).doesNotContain("answers", "answerValue", "totalScore", "item9Answer");
	}

	@Test
	void replaysAliasesRejectsConflictingKeysAndInvalidEvidenceWithoutChangingV1() throws Exception {
		var userId = insertProfile();
		var otherUser = insertProfile();
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", "MODERATELY_SEVERE", false);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "SEVERE", false);
		var foreignGad7 = insertAssessment(otherUser, GAD7_DEFINITION, "GAD7", "MILD", false);
		var incompatible = insertAssessment(userId, INCOMPATIBLE_PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var key = "support-v2-replay-0001";

		var evaluationId = create(userId, phq9, gad7, key);
		assertThat(create(userId, phq9, gad7, key)).isEqualTo(evaluationId);
		assertThat(create(userId, phq9, gad7, "support-v2-alias-00001")).isEqualTo(evaluationId);
		mvc.perform(post("/api/v2/support-evaluations").with(user(userId)).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, UUID.randomUUID())))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		mvc.perform(post("/api/v2/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-v2-foreign-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, foreignGad7)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ASSESSMENT_NOT_FOUND"));
		mvc.perform(post("/api/v2/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-v2-version-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(incompatible, gad7)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPORT_EVIDENCE_INCOMPATIBLE"));
		mvc.perform(post("/api/v2/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-v2-duplicate-001")
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, phq9)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		assertThat(jdbc.sql("select count(*) from support_evaluation_v2 where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_evaluation_v2_request where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from support_evaluation where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isZero();
	}

	@Test
	void serializesConcurrentCreatesAndRollsBackAggregateWhenOutboxCannotPersist() throws Exception {
		var userId = insertProfile();
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "MILD", false);
		var command = new SupportEvaluationV2Service.EvaluationCommand(phq9, gad7);
		var gate = new CountDownLatch(1);
		UUID evaluationId;
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				gate.await();
				return service.evaluate(userId, "support-v2-race-first-01", UUID.randomUUID(), command);
			});
			var second = executor.submit(() -> {
				gate.await();
				return service.evaluate(userId, "support-v2-race-second-1", UUID.randomUUID(), command);
			});
			gate.countDown();
			var firstResult = first.get();
			var secondResult = second.get();
			assertThat(firstResult.supportEvaluationId()).isEqualTo(secondResult.supportEvaluationId());
			evaluationId = firstResult.supportEvaluationId();
		}
		assertThat(jdbc.sql("select count(*) from support_evaluation_v2 where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_evaluation_v2_request where user_id=:userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id=:id")
				.param("id", evaluationId).query(Long.class).single()).isEqualTo(1);

		var rollbackUser = insertProfile();
		var rollbackPhq9 = insertAssessment(rollbackUser, PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var rollbackGad7 = insertAssessment(rollbackUser, GAD7_DEFINITION, "GAD7", "MILD", false);
		assertThatThrownBy(() -> service.evaluate(rollbackUser, "support-v2-rollback-0001", null,
				new SupportEvaluationV2Service.EvaluationCommand(rollbackPhq9, rollbackGad7)))
				.isInstanceOf(RuntimeException.class);
		assertThat(jdbc.sql("select count(*) from support_evaluation_v2 where user_id=:userId")
				.param("userId", rollbackUser).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select count(*) from support_evaluation_v2_request where user_id=:userId")
				.param("userId", rollbackUser).query(Long.class).single()).isZero();
	}

	@Test
	void v1AndV2RemainReadableForTheSameEvidencePair() throws Exception {
		var userId = insertProfile();
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", "MILD", true);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", "SEVERE", false);
		var v1 = mvc.perform(post("/api/v1/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-v1-compat-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.policyVersion").value("mb-support-routing-capstone-v1"))
				.andExpect(jsonPath("$.supportTier").value("SAFETY_FOLLOW_UP_RECOMMENDED"))
				.andReturn();
		var v1Id = objectMapper.readTree(v1.getResponse().getContentAsString()).get("supportEvaluationId").asText();
		var v2Id = create(userId, phq9, gad7, "support-v2-compat-0001");

		mvc.perform(get("/api/v1/support-evaluations/{id}", v1Id).with(user(userId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.supportTier").exists());
		mvc.perform(get("/api/v2/support-evaluations/{id}", v2Id).with(user(userId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.contributingDomains.length()").value(2))
				.andExpect(jsonPath("$.supportTier").doesNotExist());
		assertThat(jdbc.sql("select count(*) from support_evaluation where id=:id")
				.param("id", UUID.fromString(v1Id)).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_evaluation_v2 where id=:id")
				.param("id", v2Id).query(Long.class).single()).isEqualTo(1);
	}

	private UUID create(UUID userId, UUID phq9, UUID gad7, String key) throws Exception {
		var response = mvc.perform(post("/api/v2/support-evaluations").with(user(userId))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated()).andReturn();
		return UUID.fromString(objectMapper.readTree(response.getResponse().getContentAsString())
				.get("supportEvaluationId").asText());
	}

	private UUID insertProfile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id,display_name) values (:id,'Support v2 test user')")
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
