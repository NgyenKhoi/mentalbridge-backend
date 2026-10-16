package com.mentalbridge.care.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CombinedScreeningMeaningIntegrationTests extends CareTestProperties {

	private static final String PATH = "/api/v1/support-evaluations/{id}/combined-meaning";
	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper mapper;
	@Autowired SupportEvaluationService v1;
	@Autowired SupportEvaluationV2Service v2;

	@Test
	void historyPagesByCompletionWithCrossedStartOrderTiesAndUnfinishedFallback() throws Exception {
		var userId = profile();
		var lateCompletion = UUID.fromString("70000000-0000-4000-8000-000000000103");
		var tiedCompletion = UUID.fromString("70000000-0000-4000-8000-000000000101");
		var earlierCompletion = UUID.fromString("70000000-0000-4000-8000-000000000102");
		var unfinished = UUID.fromString("70000000-0000-4000-8000-000000000104");
		historyEpisode(userId, lateCompletion, "2026-10-01T08:00:00Z", "2026-10-15T09:00:00Z");
		historyEpisode(userId, earlierCompletion, "2026-10-12T08:00:00Z", "2026-10-12T10:00:00Z");
		historyEpisode(userId, tiedCompletion, "2026-10-10T08:00:00Z", "2026-10-15T09:00:00Z");
		historyEpisode(userId, unfinished, "2026-10-14T08:00:00Z", null);
		historyEpisode(profile(), UUID.randomUUID(), "2026-10-16T08:00:00Z", null);

		var fullResponse = mvc.perform(get("/api/v1/screening-episodes/history").param("limit", "10").with(user(userId)))
				.andExpect(status().isOk()).andReturn().getResponse();
		var fullOrder = new ArrayList<UUID>();
		mapper.readTree(fullResponse.getContentAsString()).path("items")
				.forEach(item -> fullOrder.add(UUID.fromString(item.path("episodeId").asText())));
		assertThat(fullOrder).containsExactly(lateCompletion, tiedCompletion, unfinished, earlierCompletion);

		var seen = new ArrayList<UUID>();
		String cursor = null;
		for (int index = 0; index < 4; index++) {
			var request = get("/api/v1/screening-episodes/history").param("limit", "1").with(user(userId));
			if (cursor != null) request.param("cursor", cursor);
			var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
			var page = mapper.readTree(response.getContentAsString());
			assertThat(page.path("items").size()).isEqualTo(1);
			var item = page.path("items").get(0);
			seen.add(UUID.fromString(item.path("episodeId").asText()));
			assertThat(item.path("meaningState").asText()).isEqualTo(index == 2 ? "INCOMPLETE" : "AVAILABLE");
			assertThat(page.path("hasMore").asBoolean()).isEqualTo(index < 3);
			if (index < 3) {
				cursor = page.path("nextCursor").asText();
				assertThat(cursor).isNotBlank();
			}
			else assertThat(page.path("nextCursor").isNull()).isTrue();
		}
		assertThat(seen).containsExactly(lateCompletion, tiedCompletion, unfinished, earlierCompletion);
	}

	@Test
	void readsEveryDraftBasePairWithoutMutationsAndPreservesOldResponseShapes() throws Exception {
		var userId = profile();
		for (var phq : new String[] { "MINIMAL", "MILD", "MODERATE", "MODERATELY_SEVERE", "SEVERE" }) {
			for (var gad : new String[] { "MINIMAL", "MILD", "MODERATE", "SEVERE" }) {
				var phqId = assessment(userId, PHQ9_DEFINITION, phq, false);
				var gadId = assessment(userId, GAD7_DEFINITION, gad, false);
				var source = evaluate(userId, phqId, gadId);
				var first = mvc.perform(get(PATH, source.supportEvaluationId()).with(user(userId)))
						.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
						.andExpect(jsonPath("$.baseMeaning.code").value("PHQ9_" + phq + "_GAD7_" + gad))
						.andExpect(jsonPath("$.evidence[0].assessmentId").value(phqId.toString()))
						.andExpect(jsonPath("$.evidence[1].assessmentId").value(gadId.toString()))
						.andExpect(jsonPath("$.supportTier").value(source.supportTier().name()))
						.andExpect(jsonPath("$.baseMeaning.supportTier").value(source.supportTier().name()))
						.andExpect(jsonPath("$.contentVersion").value(DraftCombinedScreeningMeaningCatalog.CONTENT_VERSION))
						.andExpect(jsonPath("$.totalScore").doesNotExist()).andReturn();
				var repeated = mvc.perform(get(PATH, source.supportEvaluationId()).with(user(userId)))
						.andExpect(status().isOk()).andReturn();
				assertThat(mapper.readTree(repeated.getResponse().getContentAsString()))
						.isEqualTo(mapper.readTree(first.getResponse().getContentAsString()));
				mvc.perform(get("/api/v1/support-evaluations/{id}", source.supportEvaluationId()).with(user(userId)))
						.andExpect(status().isOk()).andExpect(jsonPath("$.combinedMeaning").doesNotExist())
						.andExpect(jsonPath("$.baseMeaning").doesNotExist());
				var domain = v2.evaluate(userId, "combined-v2-" + UUID.randomUUID(), UUID.randomUUID(),
						new SupportEvaluationV2Service.EvaluationCommand(phqId, gadId));
				mvc.perform(get("/api/v2/support-evaluations/{id}", domain.supportEvaluationId()).with(user(userId)))
						.andExpect(status().isOk()).andExpect(jsonPath("$.supportTier").doesNotExist())
						.andExpect(jsonPath("$.combinedMeaning").doesNotExist());
				assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id=:id")
						.param("id", source.supportEvaluationId()).query(Long.class).single()).isEqualTo(1);
			}
		}
		assertThat(jdbc.sql("select count(*) from support_evaluation where user_id=:id")
				.param("id", userId).query(Long.class).single()).isEqualTo(20);
	}

	@Test
	void newerStandaloneResultsDoNotReplaceHistoricalPairAndPositiveSafetyHasPrecedence() throws Exception {
		var userId = profile();
		var phqId = assessment(userId, PHQ9_DEFINITION, "MILD", true);
		var gadId = assessment(userId, GAD7_DEFINITION, "MINIMAL", false);
		var source = evaluate(userId, phqId, gadId);
		assessment(userId, PHQ9_DEFINITION, "SEVERE", false);
		assessment(userId, GAD7_DEFINITION, "SEVERE", false);
		mvc.perform(get(PATH, source.supportEvaluationId()).with(user(userId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.presentationPriority").value("SAFETY"))
				.andExpect(jsonPath("$.supportTier").value("SAFETY_FOLLOW_UP_RECOMMENDED"))
				.andExpect(jsonPath("$.baseMeaning.supportTier").value("SELF_GUIDED_SUPPORT"))
				.andExpect(jsonPath("$.baseMeaning.code").value("PHQ9_MILD_GAD7_MINIMAL"))
				.andExpect(jsonPath("$.evidence[0].safetyStatus").value("POSITIVE_SAFETY_SCREEN"))
				.andExpect(jsonPath("$.nextStep.code").value(source.nextStep().code()))
				.andExpect(jsonPath("$.safetyGuidance").value(source.safetyGuidance()));
	}

	@Test
	void requiresUserAndValidSubjectAndKeepsUnknownAndForeignIdentifiersOpaque() throws Exception {
		var userId = profile();
		var source = evaluate(userId, assessment(userId, PHQ9_DEFINITION, "MILD", false),
				assessment(userId, GAD7_DEFINITION, "MINIMAL", false));
		mvc.perform(get(PATH, source.supportEvaluationId())).andExpect(status().isUnauthorized());
		mvc.perform(get(PATH, source.supportEvaluationId()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isForbidden());
		mvc.perform(get(PATH, source.supportEvaluationId()).with(jwt().jwt(token -> token.subject("invalid"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER")))).andExpect(status().isUnauthorized());
		mvc.perform(get(PATH, source.supportEvaluationId()).with(jwt().jwt(token -> token.claims(claims -> claims.remove("sub")))
				.authorities(new SimpleGrantedAuthority("ROLE_USER")))).andExpect(status().isUnauthorized());
		mvc.perform(get(PATH, source.supportEvaluationId()).with(user(UUID.randomUUID())))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPORT_EVALUATION_NOT_FOUND"));
		mvc.perform(get(PATH, UUID.randomUUID()).with(user(userId)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPORT_EVALUATION_NOT_FOUND"));
		mvc.perform(get(PATH, "invalid").with(user(userId))).andExpect(status().isBadRequest());
	}

	@Test
	void voidedSavedEvidenceBecomesUnavailableRatherThanSelectingAReplacement() throws Exception {
		var userId = profile();
		var phqId = assessment(userId, PHQ9_DEFINITION, "MILD", false);
		var gadId = assessment(userId, GAD7_DEFINITION, "MINIMAL", false);
		var source = evaluate(userId, phqId, gadId);
		jdbc.sql("update assessment_submission set voided_at=now(),void_reason_code='TEST_VOID' where id=:id")
				.param("id", gadId).update();
		assessment(userId, GAD7_DEFINITION, "MILD", false);
		mvc.perform(get(PATH, source.supportEvaluationId()).with(user(userId)))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("COMBINED_SCREENING_MEANING_UNAVAILABLE"));
		assertThat(jdbc.sql("select gad7_assessment_id from support_evaluation where id=:id")
				.param("id", source.supportEvaluationId()).query(UUID.class).single()).isEqualTo(gadId);
	}

	private void historyEpisode(UUID userId, UUID id, String startedAt, String completedAt) {
		if (completedAt == null) {
			jdbc.sql("""
					insert into screening_episode (id,user_id,purpose,status,created_at,updated_at)
					values (:id,:userId,'INITIAL_CHECK','IN_PROGRESS',:startedAt,:startedAt)
					""").param("id", id).param("userId", userId).param("startedAt", OffsetDateTime.parse(startedAt)).update();
			return;
		}
		var phq9 = assessment(userId, PHQ9_DEFINITION, "MILD", false);
		var gad7 = assessment(userId, GAD7_DEFINITION, "MINIMAL", false);
		var presentation = evaluate(userId, phq9, gad7);
		var domain = v2.evaluate(userId, "history-order-" + UUID.randomUUID(), UUID.randomUUID(),
				new SupportEvaluationV2Service.EvaluationCommand(phq9, gad7));
		jdbc.sql("""
				insert into screening_episode (id,user_id,purpose,status,phq9_assessment_id,gad7_assessment_id,
				    support_evaluation_id,presentation_evaluation_id,created_at,updated_at,completed_at)
				values (:id,:userId,'INITIAL_CHECK','COMPLETED',:phq9,:gad7,:domain,:presentation,:startedAt,:completedAt,:completedAt)
				""").param("id", id).param("userId", userId).param("phq9", phq9).param("gad7", gad7)
				.param("domain", domain.supportEvaluationId()).param("presentation", presentation.supportEvaluationId())
				.param("startedAt", OffsetDateTime.parse(startedAt)).param("completedAt", OffsetDateTime.parse(completedAt)).update();
	}

	private SupportEvaluationService.EvaluationView evaluate(UUID userId, UUID phq9, UUID gad7) {
		return v1.evaluate(userId, "combined-draft-" + UUID.randomUUID(), UUID.randomUUID(),
				new SupportEvaluationService.EvaluationCommand(phq9, gad7));
	}

	private UUID profile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id,display_name) values (:id,'Combined meaning test')")
				.param("id", userId).update();
		return userId;
	}

	private UUID assessment(UUID userId, UUID definition, String level, boolean positive) {
		var id = UUID.randomUUID();
		var phq9 = definition.equals(PHQ9_DEFINITION);
		jdbc.sql("""
				insert into assessment_submission (id,user_id,definition_id,idempotency_key,request_hash,privacy_policy_version,submitted_at)
				values (:id,:userId,:definition,:key,:hash,'privacy-capstone-v3',now())
				""").param("id", id).param("userId", userId).param("definition", definition)
				.param("key", "combined-assessment-" + UUID.randomUUID()).param("hash", "0".repeat(64)).update();
		int score = switch (level) {
			case "MINIMAL" -> positive ? 1 : 0;
			case "MILD" -> 5;
			case "MODERATE" -> 10;
			case "MODERATELY_SEVERE" -> 15;
			case "SEVERE" -> phq9 ? 20 : 15;
			default -> throw new IllegalArgumentException();
		};
		jdbc.sql("""
				insert into assessment_result (submission_id,total_score,screening_level,scoring_version,safety_item_positive,safety_status,safety_policy_version,disclaimer_code,calculated_at)
				values (:id,:score,:level,:scoring,:positive,:safety,:safetyPolicy,'SCREENING_NOT_DIAGNOSIS',now())
				""").param("id", id).param("score", score).param("level", level)
				.param("scoring", phq9 ? "phq9-standard-bands-v1" : "gad7-standard-bands-v1")
				.param("positive", phq9 ? positive : null)
				.param("safety", phq9 ? positive ? "POSITIVE_SAFETY_SCREEN" : "NEGATIVE_SAFETY_SCREEN" : "NOT_APPLICABLE")
				.param("safetyPolicy", phq9 ? "MB-SAFETY-PHQ9-001-test-v1" : null).update();
		return id;
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
