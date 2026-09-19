package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.EntitlementSource;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.entitlement.EntitlementClient;
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
class SupportPlanIntegrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper objectMapper;
	@MockitoBean EntitlementClient entitlements;
	@MockitoBean ResourceEligibilityClient eligibility;

	@BeforeEach
	void dependencies() {
		when(entitlements.current(any(), anyString(), any())).thenAnswer(invocation -> paid(invocation.getArgument(0)));
		eligible(ResourceEligibilityOutcome.ELIGIBLE, ResourceEligibilityReasonCode.ELIGIBLE_MATCH);
	}

	@Test
	void createsOneBoundedPaidDraftAndReloadsTheExactPersistedSnapshot() throws Exception {
		var userId = insertProfile();
		var evaluationId = evaluation(userId, "MILD", "MODERATE", true);

		var created = mvc.perform(post("/api/v1/support-plans").with(user(userId))
				.header("Idempotency-Key", "support-plan-create-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(evaluationId)))
				.andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.entitlement.packageCode").value("PLUS"))
				.andExpect(jsonPath("$.source.evaluationVersion").value(2))
				.andExpect(jsonPath("$.source.selectionPolicyVersion").value("mb-support-plan-selection-v1"))
				.andExpect(jsonPath("$.safety.guidanceCode").value("REVIEW_SAFETY_GUIDANCE"))
				.andExpect(jsonPath("$.selectedResourceCount").value(3))
				.andExpect(jsonPath("$.disclaimerCode").value("WELLBEING_SUPPORT_NOT_TREATMENT"))
				.andReturn().getResponse().getContentAsString();
		var replayed = mvc.perform(post("/api/v1/support-plans").with(user(userId))
				.header("Idempotency-Key", "support-plan-create-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(evaluationId)))
				.andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
				.andReturn().getResponse().getContentAsString();
		var differentEvaluationId = evaluation(userId, "MINIMAL", "MINIMAL", false);
		mvc.perform(post("/api/v1/support-plans").with(user(userId))
				.header("Idempotency-Key", "support-plan-create-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(differentEvaluationId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		var reloaded = mvc.perform(get("/api/v1/support-plans/current-draft").with(user(userId)))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
				.andReturn().getResponse().getContentAsString();

		assertThat(objectMapper.readTree(replayed)).isEqualTo(objectMapper.readTree(created));
		assertThat(objectMapper.readTree(reloaded)).isEqualTo(objectMapper.readTree(created));
		assertThat(jdbc.sql("select count(*) from support_plan where user_id = :id")
				.param("id", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_request where user_id = :id")
				.param("id", userId).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void freeEntitlementFailsWithoutCreatingAPlan() throws Exception {
		var userId = insertProfile();
		var evaluationId = evaluation(userId, "MILD", "MILD", false);
		when(entitlements.current(any(), anyString(), any())).thenReturn(new CurrentEntitlementResponse(userId,
				ServicePackage.FREE, EntitlementSource.DEFAULT_FREE, null, null, null,
				"service-entitlement-v1", 0, Instant.parse("2026-09-19T00:00:00Z")));

		mvc.perform(post("/api/v1/support-plans").with(user(userId))
				.header("Idempotency-Key", "support-plan-free-00001")
				.contentType(MediaType.APPLICATION_JSON).content(body(evaluationId)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_ENTITLEMENT_REQUIRED"));

		assertThat(jdbc.sql("select count(*) from support_plan where user_id = :id")
				.param("id", userId).query(Long.class).single()).isZero();
	}

	@Test
	void staleAndUnavailableEligibilityFailClosedWithoutCreation() throws Exception {
		var staleUser = insertProfile();
		var staleEvaluation = evaluation(staleUser, "MILD", "MILD", false);
		eligible(ResourceEligibilityOutcome.STALE, ResourceEligibilityReasonCode.CONTENT_VERSION_STALE);

		mvc.perform(post("/api/v1/support-plans").with(user(staleUser))
				.header("Idempotency-Key", "support-plan-stale-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(staleEvaluation)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_VERSION_STALE"));

		var unavailableUser = insertProfile();
		var unavailableEvaluation = evaluation(unavailableUser, "MILD", "MILD", false);
		eligible(ResourceEligibilityOutcome.UNAVAILABLE, ResourceEligibilityReasonCode.DEPENDENCY_UNAVAILABLE);
		mvc.perform(post("/api/v1/support-plans").with(user(unavailableUser))
				.header("Idempotency-Key", "support-plan-unavailable-1")
				.contentType(MediaType.APPLICATION_JSON).content(body(unavailableEvaluation)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("RESOURCE_ELIGIBILITY_UNAVAILABLE"));

		assertThat(jdbc.sql("select count(*) from support_plan").query(Long.class).single()).isZero();
	}

	@Test
	void staleEvaluationPolicyFailsClosedBeforeResolvingResources() throws Exception {
		var userId = insertProfile();
		var evaluationId = evaluation(userId, "MILD", "MILD", false);
		jdbc.sql("""
				update assessment_result set scoring_version = 'retired-scoring'
				where submission_id = (
					select phq9_assessment_id from support_evaluation_v2 where id = :id
				)
				""").param("id", evaluationId).update();

		mvc.perform(post("/api/v1/support-plans").with(user(userId))
				.header("Idempotency-Key", "support-plan-stale-source")
				.contentType(MediaType.APPLICATION_JSON).content(body(evaluationId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUPPORT_EVALUATION_STALE"));

		assertThat(jdbc.sql("select count(*) from support_plan where user_id = :id")
				.param("id", userId).query(Long.class).single()).isZero();
	}

	@Test
	void ownershipAndMissingDraftAreNotDisclosed() throws Exception {
		var owner = insertProfile();
		var other = insertProfile();
		var evaluationId = evaluation(owner, "MILD", "MILD", false);

		mvc.perform(post("/api/v1/support-plans").with(user(other))
				.header("Idempotency-Key", "support-plan-owner-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(evaluationId)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPORT_EVALUATION_NOT_FOUND"));
		mvc.perform(get("/api/v1/support-plans/current-draft").with(user(other)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_DRAFT_NOT_FOUND"));
	}

	@Test
	void duplicateAndConcurrentRequestsReturnOneCurrentDraft() throws Exception {
		var userId = insertProfile();
		var evaluationId = evaluation(userId, "MILD", "MILD", false);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> createAfter(start, userId, evaluationId, "support-plan-concurrent-1"));
			var second = executor.submit(() -> createAfter(start, userId, evaluationId, "support-plan-concurrent-2"));
			start.countDown();
			assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(201);
			assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(201);
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(jdbc.sql("select count(*) from support_plan where user_id = :id and status = 'DRAFT'")
				.param("id", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_request where user_id = :id")
				.param("id", userId).query(Long.class).single()).isEqualTo(2);
	}

	private int createAfter(CountDownLatch start, UUID userId, UUID evaluationId, String key) throws Exception {
		start.await(5, TimeUnit.SECONDS);
		return mvc.perform(post("/api/v1/support-plans").with(user(userId)).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body(evaluationId))).andReturn().getResponse().getStatus();
	}

	private void eligible(ResourceEligibilityOutcome outcome, ResourceEligibilityReasonCode reason) {
		doAnswer(invocation -> {
			var request = (com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest) invocation.getArgument(0);
			var results = request.requests().stream().map(query -> new ResourceEligibilityResult(query.requestId(),
					query.resourceId(), query.contentVersion(), outcome, reason,
					outcome == ResourceEligibilityOutcome.ELIGIBLE
							? query.requiredRole().name().equals("PRIMARY") ? EligibilityRole.PRIMARY : EligibilityRole.ADJUNCT
							: null,
					outcome == ResourceEligibilityOutcome.ELIGIBLE ? UUID.randomUUID().toString() : null,
					outcome == ResourceEligibilityOutcome.ELIGIBLE ? ResourceCategory.ARTICLE : null,
					outcome == ResourceEligibilityOutcome.ELIGIBLE ? "Tài nguyên hỗ trợ đã duyệt" : null,
					outcome == ResourceEligibilityOutcome.ELIGIBLE ? "Nội dung sức khỏe tổng quát đã được rà soát." : null,
					null)).toList();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-19T00:00:00Z", results);
		}).when(eligibility).resolve(any(), anyString(), any());
	}

	private CurrentEntitlementResponse paid(UUID userId) {
		return new CurrentEntitlementResponse(userId, ServicePackage.PLUS, EntitlementSource.DEMO, "mb372-test",
				Instant.parse("2026-09-18T00:00:00Z"), Instant.parse("2026-10-18T00:00:00Z"),
				"service-entitlement-v1", 1, Instant.parse("2026-09-19T00:00:00Z"));
	}

	private UUID evaluation(UUID userId, String phqLevel, String gadLevel, boolean positive) throws Exception {
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", phqLevel, positive);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", gadLevel, false);
		var response = mvc.perform(post("/api/v2/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-evaluation-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"phq9AssessmentId\":\"" + phq9 + "\",\"gad7AssessmentId\":\"" + gad7 + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("supportEvaluationId").asText());
	}

	private UUID insertProfile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id,display_name) values (:id,'SupportPlan test user')")
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
		int score = switch (level) { case "MINIMAL" -> 3; case "MILD" -> 7; default -> 12; };
		jdbc.sql("""
				insert into assessment_result
					(submission_id,total_score,screening_level,scoring_version,safety_item_positive,
					 safety_status,safety_policy_version,disclaimer_code,calculated_at)
				values (:id,:score,:level,:scoringVersion,:positive,:safetyStatus,:safetyPolicy,
				'SCREENING_NOT_DIAGNOSIS',now())
				""").param("id", id).param("score", score).param("level", level)
				.param("scoringVersion", gad7 ? "gad7-standard-bands-v1" : "phq9-standard-bands-v1")
				.param("positive", gad7 ? null : positive)
				.param("safetyStatus", gad7 ? "NOT_APPLICABLE" : positive ? "POSITIVE_SAFETY_SCREEN" : "NEGATIVE_SAFETY_SCREEN")
				.param("safetyPolicy", gad7 ? null : "MB-SAFETY-PHQ9-001-v1").update();
		return id;
	}

	private String body(UUID evaluationId) {
		return "{\"sourceSupportEvaluationId\":\"" + evaluationId + "\"}";
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString()).tokenValue("synthetic-user-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
