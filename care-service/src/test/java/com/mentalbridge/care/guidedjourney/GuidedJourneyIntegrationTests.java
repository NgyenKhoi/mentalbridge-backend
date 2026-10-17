package com.mentalbridge.care.guidedjourney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.EntitlementSource;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.entitlement.EntitlementClient;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.EligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCategory;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityReasonCode;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityResult;
import com.mentalbridge.care.shared.ApiException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GuidedJourneyIntegrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper objectMapper;
	@MockitoBean ResourceEligibilityClient eligibility;
	@MockitoBean EntitlementClient entitlements;

	@BeforeEach
	void eligibleResources() {
		when(eligibility.resolve(any(), anyString(), any())).thenAnswer(invocation -> {
			var request = (ResourceEligibilityBatchRequest) invocation.getArgument(0);
			var results = request.requests().stream().map(query -> new ResourceEligibilityResult(query.requestId(),
					query.resourceId(), query.contentVersion(), ResourceEligibilityOutcome.ELIGIBLE,
					ResourceEligibilityReasonCode.ELIGIBLE_MATCH, EligibilityRole.PRIMARY, UUID.randomUUID().toString(),
					ResourceCategory.ARTICLE, "Tài nguyên đã rà soát", "Nội dung tự hỗ trợ đã được rà soát.", null))
					.toList();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-10-16T03:00:00Z", results);
		});
	}

	@Test
	void completedInitialCheckYieldsOneGuideAcrossRetryConcurrencyLegacyGenerationAndReload() throws Exception {
		var userId = insertProfile();
		when(entitlements.current(any(), anyString(), any())).thenReturn(entitlement(userId, ServicePackage.FREE));
		mvc.perform(get("/api/v1/guided-journey/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.stage").value("ASSESSMENT_REQUIRED"))
				.andExpect(jsonPath("$.assessmentInProgress").value(false))
				.andExpect(jsonPath("$", hasKey("nextAction")))
				.andExpect(jsonPath("$.nextAction").value(nullValue()))
				.andExpect(jsonPath("$.episodeId").value(nullValue()))
				.andExpect(jsonPath("$.entitlementStatus").value("NOT_REQUIRED"));

		var episode = completeInitialCheck(userId, false);
		mvc.perform(get("/api/v1/guided-journey/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.journeyPolicyVersion").value("mb-guided-journey-v1"))
				.andExpect(jsonPath("$.stage").value("GUIDE_REQUIRED"))
				.andExpect(jsonPath("$.episodeId").value(episode.episodeId().toString()))
				.andExpect(jsonPath("$.presentationEvaluationId").value(episode.presentationEvaluationId().toString()))
				.andExpect(jsonPath("$", hasKey("supportGuideId")))
				.andExpect(jsonPath("$.supportGuideId").value(nullValue()))
				.andExpect(jsonPath("$.currentSupportPlanId").value(nullValue()))
				.andExpect(jsonPath("$.nextAction").value("EXPLORE_GUIDE_RESOURCES"))
				.andExpect(jsonPath("$.entitlementStatus").value("VERIFIED"));
		assertThat(guideCount(userId)).isZero();

		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		List<UUID> concurrentIds;
		try {
			var calls = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return ensure(userId, episode.episodeId()).andExpect(status().isOk()).andReturn();
			})).toList();
			start.countDown();
			concurrentIds = List.of(guideId(calls.get(0).get(20, TimeUnit.SECONDS)),
					guideId(calls.get(1).get(20, TimeUnit.SECONDS)));
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(concurrentIds.get(0)).isEqualTo(concurrentIds.get(1));
		var contentCalls = mockingDetails(eligibility).getInvocations().size();

		var retried = ensure(userId, episode.episodeId()).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.supportEvaluationId").value(episode.supportEvaluationId().toString()))
				.andReturn();
		assertThat(guideId(retried)).isEqualTo(concurrentIds.get(0));
		assertThat(mockingDetails(eligibility).getInvocations()).hasSize(contentCalls);

		var legacy = mvc.perform(post("/api/v1/support-guides").with(user(userId))
				.header("Idempotency-Key", "mb668-legacy-guide-0001").contentType(MediaType.APPLICATION_JSON)
				.content("{\"phq9AssessmentId\":\"" + episode.phq9AssessmentId() + "\",\"gad7AssessmentId\":\""
						+ episode.gad7AssessmentId() + "\"}"))
				.andExpect(status().isCreated()).andReturn();
		assertThat(guideId(legacy)).isEqualTo(concurrentIds.get(0));
		assertThat(guideCount(userId)).isEqualTo(1);

		mvc.perform(get("/api/v1/guided-journey/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stage").value("GUIDE_READY"))
				.andExpect(jsonPath("$.supportGuideId").value(concurrentIds.get(0).toString()));
	}

	@Test
	void newerOpenInitialCheckNeverReplacesTheCompletedGuideContext() throws Exception {
		var userId = insertProfile();
		when(entitlements.current(any(), anyString(), any())).thenReturn(entitlement(userId, ServicePackage.PLUS));
		var completed = completeInitialCheck(userId, false);
		var guide = guideId(ensure(userId, completed.episodeId()).andExpect(status().isOk()).andReturn());
		var reopened = UUID.fromString(field(mvc.perform(post("/api/v1/screening-episodes").with(user(userId))
				.contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"INITIAL_CHECK\"}"))
				.andExpect(status().isCreated()).andReturn(), "episodeId"));

		mvc.perform(get("/api/v1/guided-journey/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assessmentInProgress").value(true))
				.andExpect(jsonPath("$.episodeId").value(completed.episodeId().toString()))
				.andExpect(jsonPath("$.supportGuideId").value(guide.toString()))
				.andExpect(jsonPath("$.nextAction").value("START_PLAN_PROPOSAL"));
		ensure(userId, reopened).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SCREENING_EPISODE_INCOMPLETE"));
		assertThat(guideCount(userId)).isEqualTo(1);
	}

	@Test
	void historicalAndReassessmentEpisodesNeverPrepareAGuide() throws Exception {
		var userId = insertProfile();
		when(entitlements.current(any(), anyString(), any())).thenReturn(entitlement(userId, ServicePackage.FREE));
		var older = completeEpisode(userId, false, "INITIAL_CHECK");
		var current = completeEpisode(userId, false, "INITIAL_CHECK");
		var reassessment = completeEpisode(userId, false, "REASSESSMENT");

		ensure(userId, older.episodeId()).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SCREENING_EPISODE_NOT_CURRENT"));
		ensure(userId, reassessment.episodeId()).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SCREENING_EPISODE_NOT_CURRENT"));
		assertThat(guideCount(userId)).isZero();
		mvc.perform(get("/api/v1/guided-journey/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.episodeId").value(current.episodeId().toString()));
		ensure(userId, current.episodeId()).andExpect(status().isOk());
		assertThat(guideCount(userId)).isEqualTo(1);
	}

	@Test
	void positiveItemNineInterruptsTheOrdinaryOrderWithoutEntitlement() throws Exception {
		var userId = insertProfile();
		var episode = completeInitialCheck(userId, true);

		mvc.perform(get("/api/v1/guided-journey/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nextAction").value("REVIEW_SAFETY_SUPPORT"))
				.andExpect(jsonPath("$.entitlementStatus").value("NOT_REQUIRED"));
		ensure(userId, episode.episodeId()).andExpect(status().isOk())
				.andExpect(jsonPath("$.safety.status").value("POSITIVE_SAFETY_SCREEN"))
				.andExpect(jsonPath("$.safety.guidance").isNotEmpty());
		verifyNoInteractions(entitlements);
	}

	@Test
	void unverifiableEntitlementFailsClosedWithoutFabricatingAPaidStep() throws Exception {
		var userId = insertProfile();
		when(entitlements.current(any(), anyString(), any())).thenThrow(new ApiException(
				HttpStatus.SERVICE_UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE", "Current service entitlement could not be verified"));
		completeInitialCheck(userId, false);

		mvc.perform(get("/api/v1/guided-journey/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nextAction").value("EXPLORE_GUIDE_RESOURCES"))
				.andExpect(jsonPath("$.entitlementStatus").value("UNAVAILABLE"));
	}

	@Test
	void ensureIsOwnerScopedAndRequiresAuthenticatedUsers() throws Exception {
		var owner = insertProfile();
		var other = insertProfile();
		var episode = completeInitialCheck(owner, false);

		ensure(other, episode.episodeId()).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCREENING_EPISODE_NOT_FOUND"));
		ensure(owner, UUID.randomUUID()).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SCREENING_EPISODE_NOT_FOUND"));
		mvc.perform(get("/api/v1/guided-journey/current").with(user(other)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("ASSESSMENT_REQUIRED"));
		mvc.perform(post("/api/v1/screening-episodes/{id}/support-guide", "not-a-uuid").with(user(owner)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/guided-journey/current").with(user(owner)).header("X-Correlation-Id", "bad"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/guided-journey/current")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/guided-journey/current")
				.with(jwt().jwt(token -> token.subject(owner.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/screening-episodes/{id}/support-guide", episode.episodeId())
				.with(jwt().jwt(token -> token.subject(owner.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isForbidden());
		assertThat(guideCount(owner)).isZero();
		assertThat(guideCount(other)).isZero();
	}

	private Episode completeInitialCheck(UUID userId, boolean positiveItemNine) throws Exception {
		return completeEpisode(userId, positiveItemNine, "INITIAL_CHECK");
	}

	private Episode completeEpisode(UUID userId, boolean positiveItemNine, String purpose) throws Exception {
		var episodeId = UUID.fromString(field(mvc.perform(post("/api/v1/screening-episodes").with(user(userId))
				.contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"" + purpose + "\"}"))
				.andExpect(status().isCreated()).andReturn(), "episodeId"));
		mvc.perform(post("/api/v1/screening-episodes/{episodeId}/assessments/PHQ9", episodeId).with(user(userId))
				.header("Idempotency-Key", "mb668-phq9-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON).content(answers(PHQ9_DEFINITION, "14000000", 9,
						positiveItemNine ? 1 : 0)))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/v1/screening-episodes/{episodeId}/assessments/GAD7", episodeId).with(user(userId))
				.header("Idempotency-Key", "mb668-gad7-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON).content(answers(GAD7_DEFINITION, "13000000", 7, 0)))
				.andExpect(status().isCreated());
		var outcome = objectMapper.readTree(mvc.perform(post("/api/v1/screening-episodes/{episodeId}/support-evaluation",
				episodeId).with(user(userId))).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString()).get("episode");
		return new Episode(episodeId, UUID.fromString(outcome.get("phq9AssessmentId").asText()),
				UUID.fromString(outcome.get("gad7AssessmentId").asText()),
				UUID.fromString(outcome.get("supportEvaluationId").asText()),
				UUID.fromString(outcome.get("presentationEvaluationId").asText()));
	}

	private org.springframework.test.web.servlet.ResultActions ensure(UUID userId, UUID episodeId) throws Exception {
		return mvc.perform(post("/api/v1/screening-episodes/{episodeId}/support-guide", episodeId).with(user(userId)));
	}

	private long guideCount(UUID userId) {
		return jdbc.sql("select count(*) from support_guide where user_id=:id").param("id", userId)
				.query(Long.class).single();
	}

	private UUID guideId(MvcResult result) throws Exception {
		return UUID.fromString(field(result, "supportGuideId"));
	}

	private String field(MvcResult result, String name) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get(name).asText();
	}

	private String answers(UUID definitionId, String questionPrefix, int count, int lastValue) {
		var answers = new StringBuilder();
		for (int index = 1; index <= count; index++) {
			if (index > 1) answers.append(',');
			answers.append("{\"questionId\":\"").append(questionPrefix).append("-0000-0000-0000-")
					.append(String.format("%012d", index)).append("\",\"value\":")
					.append(index == count ? lastValue : 0).append('}');
		}
		return "{\"questionnaireDefinitionId\":\"" + definitionId
				+ "\",\"privacyPolicyVersion\":\"privacy-capstone-v3\","
				+ "\"privacyDisclosureAcknowledged\":true,\"answers\":[" + answers + "]}";
	}

	private UUID insertProfile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id, display_name) values (:id, 'Guided journey user')")
				.param("id", userId).update();
		jdbc.sql("""
				insert into consent_decision
				(id, user_id, consent_type, policy_version, granted, idempotency_key, request_hash, decided_at)
				values (:id, :userId, 'PRIVACY_POLICY', 'privacy-capstone-v3', true, :key, :requestHash, now())
				""").param("id", UUID.randomUUID()).param("userId", userId)
				.param("key", "mb668-consent-" + UUID.randomUUID()).param("requestHash", "0".repeat(64)).update();
		return userId;
	}

	private CurrentEntitlementResponse entitlement(UUID userId, ServicePackage servicePackage) {
		var now = Instant.now();
		boolean free = servicePackage == ServicePackage.FREE;
		return new CurrentEntitlementResponse(userId, servicePackage,
				free ? EntitlementSource.DEFAULT_FREE : EntitlementSource.DEMO, free ? null : "demo-mb668",
				free ? null : now, free ? null : now.plusSeconds(86400), "service-entitlement-v1", 1, now);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString()).tokenValue("synthetic-user-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private record Episode(UUID episodeId, UUID phq9AssessmentId, UUID gad7AssessmentId, UUID supportEvaluationId,
			UUID presentationEvaluationId) { }
}
