package com.mentalbridge.care.guidedjourney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
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
class HomeNextStepIntegrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper mapper;
	@MockitoBean ResourceEligibilityClient eligibility;
	@MockitoBean EntitlementClient entitlements;

	@BeforeEach
	void eligibleResources() {
		when(eligibility.resolve(any(), anyString(), any())).thenAnswer(invocation -> {
			var request = (ResourceEligibilityBatchRequest) invocation.getArgument(0);
			var results = request.requests().stream().map(query -> new ResourceEligibilityResult(query.requestId(),
					query.resourceId(), query.contentVersion(), ResourceEligibilityOutcome.ELIGIBLE,
					ResourceEligibilityReasonCode.ELIGIBLE_MATCH,
					"PRIMARY".equals(query.requiredRole().name()) ? EligibilityRole.PRIMARY : EligibilityRole.ADJUNCT,
					UUID.randomUUID().toString(), ResourceCategory.ARTICLE,
					"Tài nguyên hỗ trợ đã duyệt", "Nội dung tự hỗ trợ đã được rà soát.", null)).toList();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", Instant.now().toString(), results);
		});
	}

	@Test
	void requiresAUserAndValidSubjectCorrelationAndTimezone() throws Exception {
		var owner = insertProfile();
		mvc.perform(get("/api/v1/home-next-step")).andExpect(status().isUnauthorized());
		for (String role : java.util.List.of("ROLE_ADMIN", "ROLE_SPECIALIST")) {
			mvc.perform(get("/api/v1/home-next-step").with(jwt().jwt(token -> token.subject(owner.toString()))
					.authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isForbidden());
		}
		mvc.perform(get("/api/v1/home-next-step").with(jwt().jwt(token -> token.subject("invalid"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER")))).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/home-next-step").with(user(owner)).header("X-Correlation-Id", "invalid"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/home-next-step").with(user(owner)).param("timezone", "invalid/timezone"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void embedsTheExactJourneyAndExposesOnlyMinimizedOwnerPointers() throws Exception {
		var owner = insertProfile();
		var other = insertProfile();
		when(entitlements.current(any(), anyString(), any())).thenReturn(entitlement(owner, ServicePackage.FREE));
		var episode = completeInitialCheck(owner);
		var journey = mapper.readTree(mvc.perform(get("/api/v1/guided-journey/current").with(user(owner)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		var response = mvc.perform(get("/api/v1/home-next-step").with(user(owner))
				.param("timezone", "Asia/Ho_Chi_Minh"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.homePolicyVersion").value("mb-home-next-step-v1"))
				.andExpect(jsonPath("$.journey.episodeId").value(episode.episodeId().toString()))
				.andExpect(jsonPath("$.plan").value(nullValue()))
				.andExpect(jsonPath("$.lastCompletedPlan").value(nullValue()))
				.andReturn().getResponse().getContentAsString();
		var home = mapper.readTree(response);
		assertThat(home.path("journey")).isEqualTo(journey);
		assertThat(home.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
				"homePolicyVersion", "journey", "plan", "lastCompletedPlan");
		assertThat(home.path("journey").properties()).extracting(java.util.Map.Entry::getKey)
				.containsExactlyInAnyOrder("journeyPolicyVersion", "stage", "assessmentInProgress", "episodeId",
						"episodeCompletedAt", "presentationEvaluationId", "supportGuideId", "nextAction",
						"currentSupportPlanId", "entitlementStatus");
		assertThat(response).doesNotContain("\"answers\"", "\"totalScore\"", "\"screeningLevel\"",
				"\"journal\"", "\"emotion\"", "\"overallSeverity\"");
		mvc.perform(get("/api/v1/home-next-step").with(user(other)).param("userId", owner.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.journey.stage").value("ASSESSMENT_REQUIRED"))
				.andExpect(jsonPath("$.journey.episodeId").value(nullValue()))
				.andExpect(jsonPath("$.plan").value(nullValue()))
				.andExpect(jsonPath("$.lastCompletedPlan").value(nullValue()));
		assertThat(jdbc.sql("select count(*) from support_guide where user_id=:id").param("id", owner)
				.query(Long.class).single()).isZero();
	}

	@Test
	void keepsDraftAndActivePlanContextOwnerScoped() throws Exception {
		var owner = insertProfile();
		var other = insertProfile();
		when(entitlements.current(any(), anyString(), any())).thenReturn(entitlement(owner, ServicePackage.PLUS));
		var episode = completeInitialCheck(owner);
		var draft = mapper.readTree(mvc.perform(post("/api/v1/support-plans").with(user(owner))
				.header("Idempotency-Key", "mb672-plan-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"sourceSupportEvaluationId\":\"" + episode.evaluationId() + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		var planId = draft.path("supportPlanId").asText();
		mvc.perform(get("/api/v1/home-next-step").with(user(owner)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.plan").value(nullValue()))
				.andExpect(jsonPath("$.journey.nextAction").value("REVIEW_PLAN_DRAFT"));
		mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(owner))
				.header("If-Match", "\"" + draft.path("version").asLong() + "\"")
				.header("Idempotency-Key", "mb672-activate-" + UUID.randomUUID())).andExpect(status().isOk());
		mvc.perform(get("/api/v1/home-next-step").with(user(owner)))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.plan.supportPlanId").value(planId))
				.andExpect(jsonPath("$.plan.status").value("ACTIVE"))
				.andExpect(jsonPath("$.plan.cycleDay").hasJsonPath())
				.andExpect(jsonPath("$.plan.cycleDay").value(nullValue()))
				.andExpect(jsonPath("$.plan.cycleLengthDays").hasJsonPath())
				.andExpect(jsonPath("$.plan.cycleLengthDays").value(nullValue()))
				.andExpect(jsonPath("$.lastCompletedPlan").value(nullValue()));
		mvc.perform(get("/api/v1/home-next-step").with(user(other)).param("userId", owner.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.plan").value(nullValue()))
				.andExpect(jsonPath("$.journey.currentSupportPlanId").value(nullValue()))
				.andExpect(jsonPath("$.lastCompletedPlan").value(nullValue()));
	}

	@Test
	void missingPublishedPolicyRemains503WithCorrelation() throws Exception {
		var owner = insertProfile();
		completeInitialCheck(owner);
		var policy = jdbc.sql("select version from support_evaluation_v2_policy_definition "
				+ "where locale='vi-VN' and status='PUBLISHED'").query(String.class).single();
		var correlation = UUID.randomUUID();
		try {
			jdbc.sql("update support_evaluation_v2_policy_definition set status='RETIRED' where version=:version")
					.param("version", policy).update();
			mvc.perform(get("/api/v1/home-next-step").with(user(owner)).header("X-Correlation-Id", correlation))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.code").value("SUPPORT_POLICY_UNAVAILABLE"))
					.andExpect(jsonPath("$.correlationId").value(correlation.toString()));
		}
		finally {
			jdbc.sql("update support_evaluation_v2_policy_definition set status='PUBLISHED' where version=:version")
					.param("version", policy).update();
		}
	}

	@Test
	void entitlementOutagePreservesTheJourneyResourcesFallback() throws Exception {
		var owner = insertProfile();
		completeInitialCheck(owner);
		when(entitlements.current(any(), anyString(), any())).thenThrow(new ApiException(
				HttpStatus.SERVICE_UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE", "Current entitlement is unavailable"));
		mvc.perform(get("/api/v1/home-next-step").with(user(owner)))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.journey.nextAction").value("EXPLORE_GUIDE_RESOURCES"))
				.andExpect(jsonPath("$.journey.entitlementStatus").value("UNAVAILABLE"));
	}

	private Episode completeInitialCheck(UUID owner) throws Exception {
		var episode = body(mvc.perform(post("/api/v1/screening-episodes").with(user(owner))
				.contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"INITIAL_CHECK\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		var episodeId = UUID.fromString(episode.path("episodeId").asText());
		mvc.perform(post("/api/v1/screening-episodes/{id}/assessments/PHQ9", episodeId).with(user(owner))
				.header("Idempotency-Key", "mb672-phq9-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
				.content(answers(PHQ9_DEFINITION, "14000000", 9))).andExpect(status().isCreated());
		mvc.perform(post("/api/v1/screening-episodes/{id}/assessments/GAD7", episodeId).with(user(owner))
				.header("Idempotency-Key", "mb672-gad7-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
				.content(answers(GAD7_DEFINITION, "13000000", 7))).andExpect(status().isCreated());
		var outcome = body(mvc.perform(post("/api/v1/screening-episodes/{id}/support-evaluation", episodeId)
				.with(user(owner))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		return new Episode(episodeId, UUID.fromString(outcome.path("episode").path("supportEvaluationId").asText()));
	}

	private JsonNode body(String json) throws Exception {
		return mapper.readTree(json);
	}

	private String answers(UUID definitionId, String prefix, int count) {
		var values = java.util.stream.IntStream.rangeClosed(1, count)
				.mapToObj(index -> "{\"questionId\":\"" + prefix + "-0000-0000-0000-"
						+ String.format("%012d", index) + "\",\"value\":0}")
				.collect(java.util.stream.Collectors.joining(","));
		return "{\"questionnaireDefinitionId\":\"" + definitionId
				+ "\",\"privacyPolicyVersion\":\"privacy-capstone-v3\","
				+ "\"privacyDisclosureAcknowledged\":true,\"answers\":[" + values + "]}";
	}

	private UUID insertProfile() {
		var owner = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id,display_name) values (:id,'Home user')")
				.param("id", owner).update();
		jdbc.sql("""
				insert into consent_decision
				(id,user_id,consent_type,policy_version,granted,idempotency_key,request_hash,decided_at)
				values (:id,:userId,'PRIVACY_POLICY','privacy-capstone-v3',true,:key,:requestHash,now())
				""").param("id", UUID.randomUUID()).param("userId", owner)
				.param("key", "mb672-consent-" + UUID.randomUUID()).param("requestHash", "0".repeat(64)).update();
		return owner;
	}

	private CurrentEntitlementResponse entitlement(UUID owner, ServicePackage servicePackage) {
		var now = Instant.now();
		boolean free = servicePackage == ServicePackage.FREE;
		return new CurrentEntitlementResponse(owner, servicePackage,
				free ? EntitlementSource.DEFAULT_FREE : EntitlementSource.DEMO, free ? null : "mb672-test",
				free ? null : now, free ? null : now.plusSeconds(86400), "service-entitlement-v1", 1, now);
	}

	private RequestPostProcessor user(UUID owner) {
		return jwt().jwt(token -> token.subject(owner.toString()).tokenValue("synthetic-user-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private record Episode(UUID episodeId, UUID evaluationId) { }
}
