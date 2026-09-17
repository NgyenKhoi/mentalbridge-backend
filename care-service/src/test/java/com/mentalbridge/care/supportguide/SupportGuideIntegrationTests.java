package com.mentalbridge.care.supportguide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.EligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCategory;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityReasonCode;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SupportGuideIntegrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper objectMapper;
	@MockitoBean ResourceEligibilityClient eligibility;

	@BeforeEach
	void eligibleResources() {
		when(eligibility.resolve(any(), anyString(), any())).thenAnswer(invocation -> {
			var request = (com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest) invocation.getArgument(0);
			var results = request.requests().stream().map(query -> new ResourceEligibilityResult(query.requestId(),
					query.resourceId(), query.contentVersion(), ResourceEligibilityOutcome.ELIGIBLE,
					ResourceEligibilityReasonCode.ELIGIBLE_MATCH, EligibilityRole.PRIMARY, UUID.randomUUID().toString(),
					ResourceCategory.ARTICLE, "Tài nguyên tổng hợp", "Nội dung tự hỗ trợ đã được rà soát.", null)).toList();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-17T06:00:00Z", results);
		});
	}

	@Test
	void createsReloadsAndListsOneTimeGuideWithPositiveSafetyAndExactProvenance() throws Exception {
		var owner = insertProfile();
		var other = insertProfile();
		var phq9 = insertAssessment(owner, PHQ9_DEFINITION, "PHQ9", "MILD", true);
		var gad7 = insertAssessment(owner, GAD7_DEFINITION, "GAD7", "MODERATE", false);

		var response = mvc.perform(post("/api/v1/support-guides").with(user(owner, "FREE"))
				.header("Idempotency-Key", "support-guide-create-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.guideType").value("ONE_TIME_SUPPORT_GUIDE"))
				.andExpect(jsonPath("$.safety.status").value("POSITIVE_SAFETY_SCREEN"))
				.andExpect(jsonPath("$.resources.length()").value(4))
				.andExpect(jsonPath("$.phrasing.status").value("AI_UNAVAILABLE_FALLBACK"))
				.andExpect(jsonPath("$.status").doesNotExist())
				.andExpect(jsonPath("$.activities").doesNotExist())
				.andReturn();
		var json = objectMapper.readTree(response.getResponse().getContentAsString());
		var guideId = UUID.fromString(json.get("supportGuideId").asText());

		mvc.perform(get("/api/v1/support-guides/{id}", guideId).with(user(owner, "PREMIUM")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.supportGuideId").value(guideId.toString()));
		mvc.perform(get("/api/v1/support-guides").with(user(owner, "PLUS")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].supportGuideId").value(guideId.toString()));
		mvc.perform(get("/api/v1/support-guides/{id}", guideId).with(user(other, "FREE")))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPORT_GUIDE_NOT_FOUND"));

		assertThat(jdbc.sql("select count(*) from support_guide where id=:id").param("id", guideId)
				.query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_guide_resource where support_guide_id=:id")
				.param("id", guideId).query(Long.class).single()).isEqualTo(4);
		assertThat(json.toString()).doesNotContain("answers", "answerValue", "totalScore", "diagnosis", "treatment");
	}

	@Test
	void requiresAnAuthenticatedRegisteredUserRole() throws Exception {
		mvc.perform(get("/api/v1/support-guides")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/support-guides").with(jwt()
				.jwt(token -> token.subject(UUID.randomUUID().toString()).tokenValue("synthetic-admin-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isForbidden());
	}

	@Test
	void persistsStableUnavailableResourceOutcomeWhileSafetyRemainsSynchronous() throws Exception {
		stubIneligible(ResourceEligibilityOutcome.UNAVAILABLE,
				ResourceEligibilityReasonCode.DEPENDENCY_UNAVAILABLE);
		var user = insertProfile();
		var phq9 = insertAssessment(user, PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var gad7 = insertAssessment(user, GAD7_DEFINITION, "GAD7", "MILD", false);

		mvc.perform(post("/api/v1/support-guides").with(user(user, "FREE"))
				.header("Idempotency-Key", "support-guide-unavailable-01")
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.resourceResolution.status").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.resources.length()").value(0))
				.andExpect(jsonPath("$.safety.guidance").isNotEmpty());
	}

	@Test
	void persistsStableStaleOutcomeWithoutPresentingOutdatedResources() throws Exception {
		stubIneligible(ResourceEligibilityOutcome.STALE,
				ResourceEligibilityReasonCode.CONTENT_VERSION_STALE);
		var user = insertProfile();
		var phq9 = insertAssessment(user, PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var gad7 = insertAssessment(user, GAD7_DEFINITION, "GAD7", "MILD", false);

		mvc.perform(post("/api/v1/support-guides").with(user(user, "PLUS"))
				.header("Idempotency-Key", "support-guide-stale-000001")
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.resourceResolution.status").value("STALE"))
				.andExpect(jsonPath("$.resources.length()").value(0))
				.andExpect(jsonPath("$.safety.guidance").isNotEmpty());
	}

	@Test
	void persistsStableEmptyOutcomeWhenNoReviewedResourceMatches() throws Exception {
		stubIneligible(ResourceEligibilityOutcome.INELIGIBLE,
				ResourceEligibilityReasonCode.DOMAIN_OR_PATHWAY_NOT_ELIGIBLE);
		var user = insertProfile();
		var phq9 = insertAssessment(user, PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var gad7 = insertAssessment(user, GAD7_DEFINITION, "GAD7", "MILD", false);

		mvc.perform(post("/api/v1/support-guides").with(user(user, "PREMIUM"))
				.header("Idempotency-Key", "support-guide-empty-000001")
				.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.resourceResolution.status").value("EMPTY"))
				.andExpect(jsonPath("$.resources.length()").value(0))
				.andExpect(jsonPath("$.safety.guidance").isNotEmpty());
	}

	@Test
	void serializesConcurrentIdempotentGenerationToOneImmutableGuide() throws Exception {
		var owner = insertProfile();
		var phq9 = insertAssessment(owner, PHQ9_DEFINITION, "PHQ9", "MILD", false);
		var gad7 = insertAssessment(owner, GAD7_DEFINITION, "GAD7", "MILD", false);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var calls = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return mvc.perform(post("/api/v1/support-guides").with(user(owner, "FREE"))
						.header("Idempotency-Key", "support-guide-concurrent-01")
						.contentType(MediaType.APPLICATION_JSON).content(body(phq9, gad7)))
						.andExpect(status().isCreated()).andReturn();
			})).toList();
			start.countDown();
			var first = objectMapper.readTree(calls.get(0).get(20, TimeUnit.SECONDS).getResponse().getContentAsString());
			var second = objectMapper.readTree(calls.get(1).get(20, TimeUnit.SECONDS).getResponse().getContentAsString());

			assertThat(first.get("supportGuideId").asText()).isEqualTo(second.get("supportGuideId").asText());
			assertThat(jdbc.sql("select count(*) from support_guide where user_id=:id").param("id", owner)
					.query(Long.class).single()).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private void stubIneligible(ResourceEligibilityOutcome outcome,
			ResourceEligibilityReasonCode reasonCode) {
		doAnswer(invocation -> {
			var request = (com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest) invocation.getArgument(0);
			var results = request.requests().stream().map(query -> new ResourceEligibilityResult(query.requestId(),
					query.resourceId(), query.contentVersion(), outcome, reasonCode,
					null, null, null, null, null, null)).toList();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-17T06:00:00Z", results);
		}).when(eligibility).resolve(any(), anyString(), any());
	}

	private UUID insertProfile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id,display_name) values (:id,'Support Guide user')")
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
		boolean gad7 = instrument.equals("GAD7");
		jdbc.sql("""
				insert into assessment_result
					(submission_id,total_score,screening_level,scoring_version,safety_item_positive,
					 safety_status,safety_policy_version,disclaimer_code,calculated_at)
				values (:id,:score,:level,:scoringVersion,:positive,:safetyStatus,:safetyPolicy,
				'SCREENING_NOT_DIAGNOSIS',now())
				""").param("id", id).param("score", "MODERATE".equals(level) ? 10 : 5).param("level", level)
				.param("scoringVersion", gad7 ? "gad7-standard-bands-v1" : "phq9-standard-bands-v1")
				.param("positive", gad7 ? null : positive)
				.param("safetyStatus", gad7 ? "NOT_APPLICABLE" : positive ? "POSITIVE_SAFETY_SCREEN" : "NEGATIVE_SAFETY_SCREEN")
				.param("safetyPolicy", gad7 ? null : "MB-SAFETY-PHQ9-001-v1").update();
		return id;
	}

	private String body(UUID phq9, UUID gad7) {
		return "{\"phq9AssessmentId\":\"" + phq9 + "\",\"gad7AssessmentId\":\"" + gad7 + "\"}";
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID userId, String plan) {
		return jwt().jwt(token -> token.subject(userId.toString()).claim("plan", plan).tokenValue("synthetic-user-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
