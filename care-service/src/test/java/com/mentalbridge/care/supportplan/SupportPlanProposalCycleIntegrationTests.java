package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
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
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SupportPlanProposalCycleIntegrationTests extends CareTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper mapper;
	@MockitoBean Clock clock;
	@MockitoBean EntitlementClient entitlements;
	@MockitoBean ResourceEligibilityClient eligibility;
	private Instant now;
	private CountDownLatch proposalEligibility;

	@BeforeEach
	void providers() {
		proposalEligibility = null;
		at("2026-10-01T16:00:00Z");
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new CurrentEntitlementResponse(call.getArgument(0), ServicePackage.PLUS, EntitlementSource.DEMO,
					"cycle-test", now.minusSeconds(86400), now.plusSeconds(86400), "service-entitlement-v1", 1, now);
		}).when(entitlements).current(any(), anyString(), any());
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			if (proposalEligibility != null) {
				proposalEligibility.countDown();
				assertThat(proposalEligibility.await(10, TimeUnit.SECONDS)).isTrue();
			}
			var request = call.getArgument(0, ResourceEligibilityBatchRequest.class);
			var values = request.requests().stream().map(query -> new ResourceEligibilityResult(query.requestId(),
					query.resourceId(), query.contentVersion(), ResourceEligibilityOutcome.ELIGIBLE,
					ResourceEligibilityReasonCode.ELIGIBLE_MATCH,
					"PRIMARY".equals(query.requiredRole().name()) ? EligibilityRole.PRIMARY : EligibilityRole.ADJUNCT,
					UUID.nameUUIDFromBytes(query.resourceId().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
					ResourceCategory.ARTICLE, "Reviewed resource", "Reviewed resource summary", null)).toList();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", now.toString(), values);
		}).when(eligibility).resolve(any(), anyString(), any());
	}

	@Test
	void concurrentProposalsKeepOneDraftAndRejectTheOtherWith409() throws Exception {
		UUID user = profile();
		UUID evaluation = evaluation(user);
		proposalEligibility = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> proposeAfter(start, user, evaluation, "cycle-concurrent-proposal-0001"));
			var second = executor.submit(() -> proposeAfter(start, user, evaluation, "cycle-concurrent-proposal-0002"));
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(201, 409);
		}
		assertThat(jdbc.sql("select count(*) from support_plan where user_id=:user and status='DRAFT'")
				.param("user", user).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_request where user_id=:user")
				.param("user", user).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void proposalActivationPauseResumeAndReviewDatePersistWithoutAutomaticRenewal() throws Exception {
		UUID user = profile();
		var draft = propose(user);
		UUID plan = UUID.fromString(draft.path("supportPlanId").asText());
		assertThat(draft.path("version").asLong()).isZero();
		assertThat(draft.path("cycle").path("state").asText()).isEqualTo("NOT_STARTED");
		assertThat(draft.path("cycle").path("startedOn").isNull()).isTrue();
		at("2026-10-01T17:05:00Z");
		var active = activate(user, plan);
		assertThat(active.path("cycle").path("startedOn").asText()).isEqualTo("2026-10-02");
		assertThat(active.path("cycle").path("reviewDueOn").asText()).isEqualTo("2026-10-15");
		assertThat(reviewDates(plan, "SCHEDULED")).containsExactly("2026-10-15");
		at("2026-10-03T03:00:00Z");
		transition(user, plan, 1, "PAUSED");
		at("2026-10-06T03:00:00Z");
		var paused = detail(user, plan);
		assertThat(paused.path("cycle").path("pausedDays").asInt()).isEqualTo(3);
		assertThat(paused.path("cycle").path("currentDay").asInt()).isEqualTo(2);
		assertThat(paused.path("cycle").path("reviewDueOn").asText()).isEqualTo("2026-10-18");
		var resumed = transition(user, plan, 2, "ACTIVE");
		assertThat(resumed.path("cycle").path("pausedDays").asInt()).isEqualTo(3);
		assertThat(reviewDates(plan, "SCHEDULED")).containsExactly("2026-10-18");
		assertThat(jdbc.sql("select state_reason from support_plan_activity_occurrence where support_plan_id=:id and source_action_type='REASSESSMENT_SCREENING' and local_date='2026-10-15'")
				.param("id", plan).query(String.class).single()).isEqualTo("SCHEDULE_CHANGED");
		at("2026-10-17T17:01:00Z");
		var due = detail(user, plan);
		assertThat(due.path("cycle").path("state").asText()).isEqualTo("REVIEW_DUE");
		assertThat(due.path("status").asText()).isEqualTo("ACTIVE");
		assertThat(due.path("cycle").path("cycleNumber").asInt()).isEqualTo(1);
		assertThat(due.path("version").asLong()).isEqualTo(3);
		assertThat(jdbc.sql("select count(*) from support_plan_command where support_plan_id=:id and command_type='CYCLE_CONTINUE'")
				.param("id", plan).query(Long.class).single()).isZero();
	}

	@Test
	void continuationStartsNextCycleAndIdempotentReplayKeepsOneNewReviewOccurrence() throws Exception {
		UUID user = profile();
		UUID plan = UUID.fromString(propose(user).path("supportPlanId").asText());
		activate(user, plan);
		at("2026-10-14T04:00:00Z");
		var continued = continuation(user, plan, 1, "cycle-continuation-command-0001");
		assertThat(continued.path("cycle").path("cycleNumber").asInt()).isEqualTo(2);
		assertThat(continued.path("cycle").path("startedOn").asText()).isEqualTo("2026-10-14");
		assertThat(continued.path("cycle").path("reviewDueOn").asText()).isEqualTo("2026-10-27");
		assertThat(continued.path("cycle").path("pausedDays").asInt()).isZero();
		assertThat(continued.path("cycle").path("state").asText()).isEqualTo("IN_PROGRESS");
		assertThat(continued.path("status").asText()).isEqualTo("ACTIVE");
		assertThat(continuation(user, plan, 1, "cycle-continuation-command-0001")).isEqualTo(continued);
		assertThat(reviewDates(plan, "SCHEDULED")).containsExactly("2026-10-27");
		assertThat(jdbc.sql("select count(*) from support_plan_command where support_plan_id=:id and command_type='CYCLE_CONTINUE'")
				.param("id", plan).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void concurrentContinuationsCommitOnlyOneCycleAndReviewOccurrence() throws Exception {
		UUID user = profile();
		UUID plan = UUID.fromString(propose(user).path("supportPlanId").asText());
		activate(user, plan);
		at("2026-10-14T04:00:00Z");
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> continueAfter(start, user, plan, "cycle-concurrent-continuation-0001"));
			var second = executor.submit(() -> continueAfter(start, user, plan, "cycle-concurrent-continuation-0002"));
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(200, 412);
		}
		var continued = detail(user, plan);
		assertThat(continued.path("cycle").path("cycleNumber").asInt()).isEqualTo(2);
		assertThat(continued.path("version").asLong()).isEqualTo(2);
		assertThat(reviewDates(plan, "SCHEDULED")).containsExactly("2026-10-27");
		assertThat(jdbc.sql("select count(*) from support_plan_command where support_plan_id=:id and command_type='CYCLE_CONTINUE'")
				.param("id", plan).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void continuationRejectedBeforeDueAndStaleVersionReturns412() throws Exception {
		UUID user = profile();
		UUID plan = UUID.fromString(propose(user).path("supportPlanId").asText());
		activate(user, plan);
		mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).with(user(user))
				.header("If-Match", "\"1\"").header("Idempotency-Key", "cycle-too-early-command-0001"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_CYCLE_NOT_REVIEW_DUE"));
		at("2026-10-14T04:00:00Z");
		mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).with(user(user))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "cycle-stale-command-0001"))
				.andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_VERSION_MISMATCH"));
		assertThat(detail(user, plan).path("cycle").path("cycleNumber").asInt()).isEqualTo(1);
	}

	@Test
	void continuationRevalidatesEntitlementAndLeavesTheCycleForADowngradedUser() throws Exception {
		UUID user = profile();
		UUID plan = UUID.fromString(propose(user).path("supportPlanId").asText());
		activate(user, plan);
		at("2026-10-14T04:00:00Z");
		doAnswer(call -> new CurrentEntitlementResponse(call.getArgument(0), ServicePackage.FREE,
				EntitlementSource.DEFAULT_FREE, "cycle-free", now.minusSeconds(86400), now.plusSeconds(86400),
				"service-entitlement-v1", 2, now)).when(entitlements).current(any(), anyString(), any());
		mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).with(user(user))
				.header("If-Match", "\"1\"").header("Idempotency-Key", "cycle-downgraded-command-0001"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_ENTITLEMENT_REQUIRED"));
		assertThat(detail(user, plan).path("cycle").path("cycleNumber").asInt()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_command where support_plan_id=:id and command_type='CYCLE_CONTINUE'")
				.param("id", plan).query(Long.class).single()).isZero();
	}

	@Test
	void continuationRequiresAuthenticationUserRoleAndOwnerScope() throws Exception {
		UUID user = profile();
		UUID foreignUser = profile();
		UUID plan = UUID.fromString(propose(user).path("supportPlanId").asText());
		activate(user, plan);
		at("2026-10-14T04:00:00Z");
		mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan)
				.header("If-Match", "\"1\"").header("Idempotency-Key", "cycle-unauthenticated-command-0001"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan)
				.with(jwt().jwt(token -> token.subject(user.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST")))
				.header("If-Match", "\"1\"").header("Idempotency-Key", "cycle-specialist-command-0001"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CARE_USER_REQUIRED"));
		mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).with(user(foreignUser))
				.header("If-Match", "\"1\"").header("Idempotency-Key", "cycle-foreign-command-0001"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_NOT_FOUND"));
		mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", UUID.randomUUID()).with(user(user))
				.header("If-Match", "\"1\"").header("Idempotency-Key", "cycle-missing-plan-command-0001"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_NOT_FOUND"));
		assertThat(detail(user, plan).path("version").asLong()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_command where support_plan_id=:id and command_type='CYCLE_CONTINUE'")
				.param("id", plan).query(Long.class).single()).isZero();
	}

	@Test
	void continuationRejectsMissingOrInvalidHeadersWithoutMutation() throws Exception {
		UUID user = profile();
		UUID plan = UUID.fromString(propose(user).path("supportPlanId").asText());
		activate(user, plan);
		at("2026-10-14T04:00:00Z");
		mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).with(user(user))
				.header("Idempotency-Key", "cycle-missing-match-command-0001"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).with(user(user))
				.header("If-Match", "\"1\""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		for (String match : List.of("W/\"1\"", "\"not-a-number\"", "\"9223372036854775808\"")) {
			mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).with(user(user))
					.header("If-Match", match).header("Idempotency-Key", "cycle-invalid-match-command-0001"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		for (String key : List.of("short", "cycle-continuation-\u00e1-key-0001")) {
			mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).with(user(user))
					.header("If-Match", "\"1\"").header("Idempotency-Key", key))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		var unchanged = detail(user, plan);
		assertThat(unchanged.path("version").asLong()).isEqualTo(1);
		assertThat(unchanged.path("cycle").path("cycleNumber").asInt()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_command where support_plan_id=:id and command_type='CYCLE_CONTINUE'")
				.param("id", plan).query(Long.class).single()).isZero();
	}

	@Test
	void cycleConstraintRejectsInvalidNativeLifecycleAndLegacyBackfill() throws Exception {
		UUID user = profile();
		UUID plan = UUID.fromString(propose(user).path("supportPlanId").asText());
		assertThatThrownBy(() -> jdbc.sql("update support_plan set cycle_number=0 where id=:id")
				.param("id", plan).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update support_plan set cycle_paused_days=-1 where id=:id")
				.param("id", plan).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update support_plan set cycle_paused_on=current_date where id=:id")
				.param("id", plan).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update support_plan set plan_policy_version='support-plan-policy-v3' where id=:id")
				.param("id", plan).update()).isInstanceOf(DataIntegrityViolationException.class);
		activate(user, plan);
		assertThatThrownBy(() -> jdbc.sql("update support_plan set cycle_started_on=null where id=:id")
				.param("id", plan).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update support_plan set cycle_timezone=null,cycle_number=null,cycle_started_on=null,cycle_paused_days=null where id=:id")
				.param("id", plan).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(detail(user, plan).path("cycle").path("startedOn").asText()).isEqualTo("2026-10-01");
	}

	@Test
	void resumePreservesSkippedReviewHistoryAndCreatesTheShiftedReview() throws Exception {
		UUID user = profile();
		UUID plan = UUID.fromString(propose(user).path("supportPlanId").asText());
		activate(user, plan);
		jdbc.sql("update support_plan_activity_occurrence set state='SKIPPED',skipped_at=:now where support_plan_id=:id and source_action_type='REASSESSMENT_SCREENING'")
				.param("now", Timestamp.from(now)).param("id", plan).update();
		at("2026-10-03T03:00:00Z");
		transition(user, plan, 1, "PAUSED");
		at("2026-10-05T03:00:00Z");
		transition(user, plan, 2, "ACTIVE");
		assertThat(reviewDates(plan, "SKIPPED")).containsExactly("2026-10-14");
		assertThat(reviewDates(plan, "SCHEDULED")).containsExactly("2026-10-16");
	}

	private void at(String instant) {
		now = Instant.parse(instant);
		when(clock.instant()).thenAnswer(call -> now);
	}

	private int proposeAfter(CountDownLatch start, UUID user, UUID evaluation, String key) throws Exception {
		assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
		var response = mvc.perform(post("/api/v2/support-plans").with(user(user)).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"sourceSupportEvaluationId\":\"" + evaluation + "\"}"))
				.andReturn().getResponse();
		if (response.getStatus() == 201) readPlanResponse(response);
		return response.getStatus();
	}

	private int continueAfter(CountDownLatch start, UUID user, UUID plan, String key) throws Exception {
		assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
		var response = mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).with(user(user))
				.header("If-Match", "\"1\"").header("Idempotency-Key", key))
				.andReturn().getResponse();
		if (response.getStatus() == 200) readPlanResponse(response);
		return response.getStatus();
	}

	private java.util.List<String> reviewDates(UUID plan, String state) {
		return jdbc.sql("select local_date::text from support_plan_activity_occurrence where support_plan_id=:id and source_action_type='REASSESSMENT_SCREENING' and state=:state order by local_date")
				.param("id", plan).param("state", state).query(String.class).list();
	}

	private JsonNode continuation(UUID user, UUID plan, long version, String key) throws Exception {
		return readPlanResponse(mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).with(user(user))
				.header("If-Match", '"' + Long.toString(version) + '"').header("Idempotency-Key", key))
				.andExpect(status().isOk()).andReturn().getResponse());
	}

	private JsonNode transition(UUID user, UUID plan, long version, String target) throws Exception {
		return readPlanResponse(mvc.perform(put("/api/v2/support-plans/{id}/status", plan).with(user(user))
				.header("If-Match", '"' + Long.toString(version) + '"').contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"" + target + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse());
	}

	private JsonNode detail(UUID user, UUID plan) throws Exception {
		return readPlanResponse(mvc.perform(get("/api/v2/support-plans/{id}", plan).with(user(user))).andExpect(status().isOk())
				.andReturn().getResponse());
	}

	private JsonNode activate(UUID user, UUID plan) throws Exception {
		return readPlanResponse(mvc.perform(post("/api/v2/support-plans/{id}/activate", plan).with(user(user))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "cycle-activate-" + plan))
				.andExpect(status().isOk()).andReturn().getResponse());
	}

	private JsonNode propose(UUID user) throws Exception {
		UUID evaluation = evaluation(user);
		return readPlanResponse(mvc.perform(post("/api/v2/support-plans").with(user(user)).header("Idempotency-Key", "cycle-propose-" + evaluation)
				.contentType(MediaType.APPLICATION_JSON).content("{\"sourceSupportEvaluationId\":\"" + evaluation + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse());
	}

	private JsonNode readPlanResponse(MockHttpServletResponse response) throws Exception {
		var value = read(response.getContentAsString());
		assertThat(value.path("version").isIntegralNumber()).isTrue();
		assertThat(response.getHeader("ETag")).isEqualTo('"' + Long.toString(value.path("version").asLong()) + '"');
		return value;
	}

	private JsonNode read(String json) throws Exception { return mapper.readTree(json); }
	private RequestPostProcessor user(UUID id) { return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER")); }
	private UUID profile() {
		UUID id = UUID.randomUUID();
		jdbc.sql("insert into user_profile(account_id,display_name,timezone) values(:id,'Cycle test user','Asia/Ho_Chi_Minh')").param("id", id).update();
		return id;
	}

	private UUID evaluation(UUID user) throws Exception {
		UUID phq = assessment(user, "10000000-0000-0000-0000-000000000004", false);
		UUID gad = assessment(user, "10000000-0000-0000-0000-000000000003", true);
		var evaluation = read(mvc.perform(post("/api/v2/support-evaluations").with(user(user))
				.header("Idempotency-Key", "cycle-evaluation-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"phq9AssessmentId\":\"" + phq + "\",\"gad7AssessmentId\":\"" + gad + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		UUID id = UUID.fromString(evaluation.path("supportEvaluationId").asText());
		jdbc.sql("""
				insert into screening_episode(id,user_id,purpose,status,phq9_assessment_id,gad7_assessment_id,support_evaluation_id,created_at,updated_at,completed_at,version)
				values(:id,:user,'INITIAL_CHECK','COMPLETED',:phq,:gad,:evaluation,:now,:now,:now,0)
				""").param("id", UUID.randomUUID()).param("user", user).param("phq", phq).param("gad", gad)
				.param("evaluation", id).param("now", Timestamp.from(now)).update();
		return id;
	}

	private UUID assessment(UUID user, String definition, boolean gad) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into assessment_submission(id,user_id,definition_id,idempotency_key,request_hash,privacy_policy_version,submitted_at)
				values(:id,:user,:definition,:key,:hash,'privacy-capstone-v3',:now)
				""").param("id", id).param("user", user).param("definition", UUID.fromString(definition))
				.param("key", "cycle-assessment-" + id).param("hash", "0".repeat(64)).param("now", Timestamp.from(now)).update();
		jdbc.sql("""
				insert into assessment_result(submission_id,total_score,screening_level,scoring_version,safety_item_positive,safety_status,safety_policy_version,disclaimer_code,calculated_at)
				values(:id,3,'MINIMAL',:scoring,:positive,:status,:policy,'SCREENING_NOT_DIAGNOSIS',:now)
				""").param("id", id).param("scoring", gad ? "gad7-standard-bands-v1" : "phq9-standard-bands-v1")
				.param("positive", gad ? null : false).param("status", gad ? "NOT_APPLICABLE" : "NEGATIVE_SAFETY_SCREEN")
				.param("policy", gad ? null : "MB-SAFETY-PHQ9-001-v1").param("now", Timestamp.from(now)).update();
		return id;
	}
}
