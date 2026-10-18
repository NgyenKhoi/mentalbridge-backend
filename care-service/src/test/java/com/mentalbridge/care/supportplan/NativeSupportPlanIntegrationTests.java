package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import com.mentalbridge.care.reassessment.ReassessmentSummaryView;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.EligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCategory;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityReasonCode;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityResult;
import com.mentalbridge.care.supportplan.EmotionCheckInEvidenceHttpClient.Evidence;
import com.mentalbridge.care.supportplan.EmotionCheckInEvidenceHttpClient.Fact;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NativeSupportPlanIntegrationTests extends CareTestProperties {
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper mapper;
	@MockitoBean EntitlementClient entitlements;
	@MockitoBean ResourceEligibilityClient eligibility;
	@MockitoBean EmotionCheckInEvidenceClient emotions;

	@BeforeEach
	void ownedProviders() {
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return paid(call.getArgument(0), ServicePackage.PLUS);
		}).when(entitlements).current(any(), anyString(), any());
		eligible(ResourceEligibilityOutcome.ELIGIBLE, ResourceEligibilityReasonCode.ELIGIBLE_MATCH);
	}

	@Test
	void createsANewNativeProposalOnlyFromAdmittedResourcesAndExplicitEmotionIntent() throws Exception {
		UUID userId = profile();
		preferences(userId, "EMOTION_CHECK_IN", 10, "ACTIVE", 0);
		UUID evaluation = evaluation(userId, true);
		var draft = propose(userId, evaluation, "native-emotion-propose-0001");
		UUID id = UUID.fromString(draft.path("supportPlanId").asText());
		assertThat(draft.path("representationMode").asText()).isEqualTo("NATIVE_V3");
		assertThat(draft.path("planPolicyVersion").asText()).isEqualTo(NativeSupportPlanWriter.PROPOSAL_POLICY);
		assertThat(draft.path("version").asLong()).isZero();
		var emotion = action(draft, "EMOTION_CHECK_IN");
		assertThat(emotion.path("purposeCode").asText()).isEqualTo("NOTICE_SELF_REPORTED_EMOTIONS");
		assertThat(emotion.path("source").path("ownerDomain").asText()).isEqualTo("JOURNAL_AI");
		assertThat(emotion.path("provenance").path("category").asText()).isEqualTo("USER_SELECTED");
		assertThat(emotion.path("schedules").get(0).path("status").asText()).isEqualTo("DRAFT");
		assertThat(emotion.path("schedules").get(0).path("recurrenceType").asText()).isEqualTo("DAILY");
		assertThat(action(draft, "REASSESSMENT_SCREENING").path("schedules").get(0).path("recurrenceType").asText())
				.isEqualTo("ONCE");
		assertThat(action(draft, "PROFESSIONAL_SUPPORT").path("pathRole").asText()).isEqualTo("PRIMARY");
		assertThat(action(draft, "RESOURCE").path("pathRole").asText()).isEqualTo("ADJUNCT");
		assertThat(rows("support_plan_slot", id)).isZero();
		var replay = propose(userId, evaluation, "native-emotion-propose-0001");
		assertThat(replay).isEqualTo(draft);
		assertThat(jdbc.sql("select count(*) from support_plan where user_id=:user").param("user", userId).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void proposesBoundedJournalIntentOnlyWhenSelectedWithBudgetAndProfessionalPrecedence() throws Exception {
		UUID userId = profile();
		preferences(userId, "JOURNALING", 5, "ACTIVE", 0);
		UUID evaluation = evaluation(userId, true);
		var draft = propose(userId, evaluation, "native-journal-proposal-0001");
		var journal = action(draft, "JOURNAL");
		assertThat(journal.path("purposeCode").asText()).isEqualTo("WRITE_SHORT_REFLECTION");
		assertThat(journal.path("source").path("sourceKind").asText()).isEqualTo("JOURNAL_TEMPLATE");
		assertThat(journal.path("source").path("sourceId").asText()).isEqualTo(NativeSupportPlanWriter.JOURNAL_INTENT);
		assertThat(journal.path("provenance").path("category").asText()).isEqualTo("USER_SELECTED");
		assertThat(journal.path("pathRole").asText()).isEqualTo("ADJUNCT");
		assertThat(action(draft, "PROFESSIONAL_SUPPORT").path("pathRole").asText()).isEqualTo("PRIMARY");
		assertThat(actions(draft).stream().filter(value -> "EMOTION_CHECK_IN".equals(value.path("actionType").asText())).count()).isZero();
		var schedule = journal.path("schedules").get(0);
		assertThat(schedule.path("recurrenceType").asText()).isEqualTo("WEEKLY");
		assertThat(schedule.path("estimatedDurationMinutes").asInt()).isEqualTo(5);
		assertThat(propose(userId, evaluation, "native-journal-proposal-0001")).isEqualTo(draft);
		assertThat(draft.toString()).doesNotContain("journalText", "mood", "analysis", "content.text");
		activate(userId, UUID.fromString(draft.path("supportPlanId").asText()), 0, "native-journal-activate-0001");
	}

	@Test
	void doesNotInferJournalIntentAndKeepsPreviouslyAdmittedEmotionPolicyUsable() throws Exception {
		UUID userId = profile();
		preferences(userId, "EMOTION_CHECK_IN", 15, "BALANCED", 0);
		var evaluation = evaluation(userId, false);
		var draft = propose(userId, evaluation, "native-old-policy-proposal-0001");
		assertThat(actions(draft).stream().filter(value -> "JOURNAL".equals(value.path("actionType").asText())).count()).isZero();
		UUID id = UUID.fromString(draft.path("supportPlanId").asText());
		jdbc.sql("delete from support_plan_activity_schedule where support_plan_id=:id and source_action_type='REASSESSMENT_SCREENING'")
				.param("id", id).update();
		jdbc.sql("delete from support_plan_action where support_plan_id=:id and action_type='REASSESSMENT_SCREENING'")
				.param("id", id).update();
		jdbc.sql("""
				update support_plan set plan_policy_version=:policy,action_count=action_count-1,
				cycle_timezone=null,cycle_number=null,cycle_started_on=null,cycle_paused_days=null,cycle_paused_on=null
				where id=:id
				""")
				.param("policy", NativeSupportPlanWriter.EMOTION_POLICY).param("id", id).update();
		var active = activate(userId, id, 0, "native-old-policy-activate-0001");
		preferences(userId, "JOURNALING", 15, "BALANCED", 1);
		active = response(mvc.perform(get("/api/v2/support-plans/{id}", id).with(user(userId)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(active.path("planPolicyVersion").asText()).isEqualTo(NativeSupportPlanWriter.EMOTION_POLICY);
		assertThat(active.has("cycle")).isFalse();
		assertThat(actions(active).stream().filter(value -> "JOURNAL".equals(value.path("actionType").asText())).count()).isZero();
		assertThat(actions(active).stream().filter(value -> "REASSESSMENT_SCREENING".equals(value.path("actionType").asText())).count()).isZero();
	}

	@Test
	void activatesActionsAndBudgetedCadenceThenReconcilesActualPersistedOccurrence() throws Exception {
		UUID userId = profile();
		preferences(userId, "EMOTION_CHECK_IN", 30, "ACTIVE", 0);
		var draft = propose(userId, evaluation(userId, false), "native-activate-proposal-0001");
		UUID id = UUID.fromString(draft.path("supportPlanId").asText());
		var active = activate(userId, id, draft.path("version").asLong(), "native-activate-command-0001");
		assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
		assertThat(actions(active)).allSatisfy(value -> assertThat(value.path("status").asText()).isEqualTo("ACTIVE"));
		assertThat(activate(userId, id, draft.path("version").asLong(), "native-activate-command-0001")).isEqualTo(active);
		assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id=:id and message_type='care.support-plan.activated'")
				.param("id", id).query(Long.class).single()).isEqualTo(1);
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
		var occurrences = response(mvc.perform(get("/api/v2/support-plan-occurrences").with(user(userId))
				.param("from", today.toString()).param("through", today.plusDays(6).toString()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		var emotionId = action(active, "EMOTION_CHECK_IN").path("actionId").asText();
		var occurrence = java.util.stream.StreamSupport.stream(occurrences.path("occurrences").spliterator(), false)
				.filter(value -> value.path("actionId").asText().equals(emotionId) && value.path("localDate").asText().equals(today.toString()))
				.findFirst().orElseThrow();
		doAnswer(call -> new Evidence("emotion-check-in-completion-evidence-v1", userId, today, "RECORDED",
				new Fact("JOURNAL_AI", "EMOTION_CHECK_IN", UUID.randomUUID(), "1", Instant.now().minusMillis(1), "Asia/Ho_Chi_Minh"), Instant.now()))
				.when(emotions).read(any(), any(), anyString(), any());
		mvc.perform(post("/api/v2/support-plan-occurrences/{id}/emotion-check-in/reconcile", occurrence.path("occurrenceId").asText()).with(user(userId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.occurrenceState").value("COMPLETED"));
		var total = jdbc.sql("""
				select coalesce(max(total),0) from (
				select occurrence.local_date,sum(schedule.estimated_duration_minutes) total
				from support_plan_activity_occurrence occurrence join support_plan_activity_schedule schedule on schedule.id=occurrence.activity_schedule_id
				where occurrence.support_plan_id=:id group by occurrence.local_date) days
				""").param("id", id).query(Long.class).single();
		assertThat(total).isLessThanOrEqualTo(30);
		mvc.perform(put("/api/v1/support-plans/{id}/status", id).with(user(userId)).header("If-Match", '"' + active.path("version").asText() + '"')
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAUSED\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED"));
		mvc.perform(get("/api/v2/support-plans/current").with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void absentPreferencesUseTheDailyEmotionDefaultAndFreeCannotPropose() throws Exception {
		UUID userId = profile();
		UUID evaluationId = evaluation(userId, false);
		var draft = propose(userId, evaluationId, "native-resource-only-0001");
		var emotion = action(draft, "EMOTION_CHECK_IN");
		assertThat(emotion.path("provenance").path("category").asText()).isEqualTo("SYSTEM_POLICY");
		assertThat(emotion.path("schedules").get(0).path("recurrenceType").asText()).isEqualTo("DAILY");
		UUID free = profile();
		doAnswer(call -> paid(call.getArgument(0), ServicePackage.FREE)).when(entitlements).current(any(), anyString(), any());
		mvc.perform(post("/api/v2/support-plans").with(user(free)).header("Idempotency-Key", "native-free-proposal-0001")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sourceSupportEvaluationId\":\"" + evaluation(free, false) + "\"}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_ENTITLEMENT_REQUIRED"));
		assertThat(jdbc.sql("select count(*) from support_plan where user_id=:user").param("user", free).query(Long.class).single()).isZero();
	}

	@Test
	void fiveMinuteBudgetWithDailyEmotionFailsWithoutPersistingTheProposal() throws Exception {
		UUID userId = profile();
		preferences(userId, "EMOTION_CHECK_IN", 5, "ACTIVE", 0);
		UUID evaluationId = evaluation(userId, false);

		mvc.perform(post("/api/v2/support-plans").with(user(userId))
				.header("Idempotency-Key", "native-emotion-budget-conflict-0001").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sourceSupportEvaluationId\":\"" + evaluationId + "\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_TIME_BUDGET_INCOMPATIBLE"));

		assertThat(jdbc.sql("select count(*) from support_plan where user_id=:user").param("user", userId).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select count(*) from support_plan_action where user_id=:user").param("user", userId).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select count(*) from support_plan_request where user_id=:user").param("user", userId).query(Long.class).single()).isZero();
	}

	@Test
	void changedPreferencesAndWithdrawnContentLeaveTheNativeDraftUnchanged() throws Exception {
		UUID userId = profile();
		preferences(userId, "EMOTION_CHECK_IN", 10, "ACTIVE", 0);
		var draft = propose(userId, evaluation(userId, false), "native-stale-proposal-0001");
		UUID id = UUID.fromString(draft.path("supportPlanId").asText());
		preferences(userId, "READING", 10, "ACTIVE", 1);
		mvc.perform(post("/api/v2/support-plans/{id}/activate", id).with(user(userId)).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "native-stale-activate-0001")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_PERSONALIZATION_REVIEW_REQUIRED"));
		assertThat(jdbc.sql("select status from support_plan where id=:id").param("id", id).query(String.class).single()).isEqualTo("DRAFT");
		assertThat(jdbc.sql("select count(*) from support_plan_action where support_plan_id=:id and status='ACTIVE'").param("id", id).query(Long.class).single()).isZero();
		assertThat(rows("support_plan_activity_occurrence", id)).isZero();
		eligible(ResourceEligibilityOutcome.UNAVAILABLE, ResourceEligibilityReasonCode.DEPENDENCY_UNAVAILABLE);
		mvc.perform(post("/api/v2/support-plans/{id}/activate", id).with(user(userId)).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "native-unavailable-activate-0001")).andExpect(status().isServiceUnavailable());
		assertThat(jdbc.sql("select status from support_plan where id=:id").param("id", id).query(String.class).single()).isEqualTo("DRAFT");
	}

	@Test
	void concurrentActivationKeepsOneCurrentNativePlanAndOneActivationFact() throws Exception {
		UUID userId = profile();
		preferences(userId, "EMOTION_CHECK_IN", 10, "ACTIVE", 0);
		var draft = propose(userId, evaluation(userId, false), "native-concurrent-proposal-0001");
		UUID id = UUID.fromString(draft.path("supportPlanId").asText());
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> activateAfter(start, userId, id, "native-concurrent-activate-0001"));
			var second = executor.submit(() -> activateAfter(start, userId, id, "native-concurrent-activate-0002"));
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
		}
		assertThat(jdbc.sql("select count(*) from support_plan where user_id=:user and status='ACTIVE'").param("user", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id=:id and message_type='care.support-plan.activated'").param("id", id).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void explicitLifecycleRetainsEvidenceAndAuthoritativeVersion() throws Exception {
		UUID userId = profile();
		preferences(userId, "EMOTION_CHECK_IN", 10, "ACTIVE", 0);
		var draft = propose(userId, evaluation(userId, false), "native-lifecycle-proposal-0001");
		UUID id = UUID.fromString(draft.path("supportPlanId").asText());
		activate(userId, id, 0, "native-lifecycle-activate-0001");
		mvc.perform(put("/api/v2/support-plans/{id}/status", id).with(user(userId)).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAUSED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
		mvc.perform(put("/api/v2/support-plans/{id}/status", id).with(user(userId)).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAUSED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
		mvc.perform(put("/api/v2/support-plans/{id}/status", id).with(user(userId)).header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(put("/api/v2/support-plans/{id}/status", id).with(user(userId)).header("If-Match", "\"3\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\",\"completionReason\":\"USER_DECISION\"}"))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v2/support-plans/history").with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].supportPlanId").value(id.toString()))
				.andExpect(jsonPath("$.items[0].representationMode").value("NATIVE_V3"))
				.andExpect(jsonPath("$.items[0].status").value("COMPLETED"));
		mvc.perform(get("/api/v2/support-plans/history").with(user(UUID.randomUUID()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void newNativeDraftDoesNotConvertTheExistingV2PlanAndRequiresExplicitReassessmentReplacement() throws Exception {
		UUID userId = profile();
		UUID evaluationId = evaluation(userId, false);
		var oldDraft = response(mvc.perform(post("/api/v1/support-plans").with(user(userId))
				.header("Idempotency-Key", "legacy-before-native-proposal-0001").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sourceSupportEvaluationId\":\"" + evaluationId + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		UUID oldId = UUID.fromString(oldDraft.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", oldId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "legacy-before-native-activate-0001")).andExpect(status().isOk());
		long oldSlotCount = rows("support_plan_slot", oldId);
		preferences(userId, "EMOTION_CHECK_IN", 30, "ACTIVE", 0);
		var draft = propose(userId, evaluationId, "native-replacement-proposal-0001");
		UUID draftId = UUID.fromString(draft.path("supportPlanId").asText());
		mvc.perform(get("/api/v2/support-plans/current").with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.supportPlanId").value(oldId.toString()))
				.andExpect(jsonPath("$.representationMode").value("V2_RESOURCE_ADAPTER"));
		mvc.perform(post("/api/v2/support-plans/{id}/activate", draftId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "native-existing-current-activate-0001"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_CURRENT_EXISTS"));
		UUID summaryId = summary(userId, draftId);
		String body = "{\"currentSupportPlanId\":\"" + oldId + "\",\"currentVersion\":1,\"reassessmentSummaryId\":\"" + summaryId + "\"}";
		mvc.perform(post("/api/v2/support-plans/{id}/replacement-review", draftId).with(user(userId)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE"));
		assertThat(jdbc.sql("select status from support_plan where id=:id").param("id", oldId).query(String.class).single()).isEqualTo("ACTIVE");
		String accepted = mvc.perform(post("/api/v2/support-plans/{id}/replace", draftId).with(user(userId)).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "native-replacement-confirm-0001").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andReturn().getResponse().getContentAsString();
		String replay = mvc.perform(post("/api/v2/support-plans/{id}/replace", draftId).with(user(userId)).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "native-replacement-confirm-0001").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(response(replay)).isEqualTo(response(accepted));
		assertThat(jdbc.sql("select model_version || '|' || status from support_plan where id=:id").param("id", oldId).query(String.class).single())
				.isEqualTo("2|SUPERSEDED");
		assertThat(rows("support_plan_slot", oldId)).isEqualTo(oldSlotCount);
		assertThat(rows("support_plan_action", oldId)).isZero();
		assertThat(jdbc.sql("select count(*) from support_plan_command where user_id=:user and command_type='REPLACE' and source_support_plan_id=:old and reassessment_summary_id=:summary")
				.param("user", userId).param("old", oldId).param("summary", summaryId).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void anIncompatibleExistingV2DraftIsNotSilentlyReplacedOrAliased() throws Exception {
		UUID userId = profile();
		UUID evaluation = evaluation(userId, false);
		mvc.perform(post("/api/v1/support-plans").with(user(userId)).header("Idempotency-Key", "legacy-draft-preservation-0001")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sourceSupportEvaluationId\":\"" + evaluation + "\"}"))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/v2/support-plans").with(user(userId)).header("Idempotency-Key", "native-conflicting-draft-0001")
				.contentType(MediaType.APPLICATION_JSON).content("{\"sourceSupportEvaluationId\":\"" + evaluation + "\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_DRAFT_EXISTS"));
		assertThat(jdbc.sql("select count(*) from support_plan where user_id=:user and model_version=3").param("user", userId).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select count(*) from support_plan_request where user_id=:user and idempotency_key='native-conflicting-draft-0001'").param("user", userId).query(Long.class).single()).isZero();
	}

	@Test
	void nativeResourceAcknowledgementAndEmotionSkipCannotForgeOrEraseExternalCompletion() throws Exception {
		UUID userId = profile();
		preferences(userId, "EMOTION_CHECK_IN", 30, "ACTIVE", 0);
		var draft = propose(userId, evaluation(userId, false), "native-user-state-proposal-0001");
		UUID planId = UUID.fromString(draft.path("supportPlanId").asText());
		activate(userId, planId, 0, "native-user-state-activate-0001");
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
		var window = response(mvc.perform(get("/api/v2/support-plan-occurrences").with(user(userId))
				.param("from", today.toString()).param("through", today.plusDays(1).toString()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		var values = java.util.stream.StreamSupport.stream(window.path("occurrences").spliterator(), false).toList();
		String resource = values.stream().filter(value -> "RESOURCE".equals(value.path("actionType").asText())
				&& today.toString().equals(value.path("localDate").asText())).findFirst().orElseThrow().path("occurrenceId").asText();
		String emotion = values.stream().filter(value -> "EMOTION_CHECK_IN".equals(value.path("actionType").asText())
				&& today.toString().equals(value.path("localDate").asText())).findFirst().orElseThrow().path("occurrenceId").asText();
		mvc.perform(put("/api/v2/support-plan-occurrences/{id}/state", emotion).with(user(userId)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"COMPLETED\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUTHORITATIVE_COMPLETION_REQUIRED"));
		mvc.perform(put("/api/v2/support-plan-occurrences/{id}/state", emotion).with(user(userId)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"SKIPPED\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SKIPPED"))
				.andExpect(jsonPath("$.completionEvidence").isEmpty()).andExpect(header().string("ETag", "\"1\""));
		mvc.perform(put("/api/v2/support-plan-occurrences/{id}/state", emotion).with(user(userId)).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"SCHEDULED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
		mvc.perform(put("/api/v2/support-plan-occurrences/{id}/state", emotion).with(user(userId)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"SKIPPED\"}"))
				.andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("OCCURRENCE_VERSION_MISMATCH"));
		mvc.perform(put("/api/v2/support-plan-occurrences/{id}/state", resource).with(user(userId)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"COMPLETED\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.completionEvidence.ownerDomain").value("CARE"))
				.andExpect(jsonPath("$.completionEvidence.kind").value("USER_ACKNOWLEDGEMENT"));
		mvc.perform(put("/api/v2/support-plan-occurrences/{id}/state", resource).with(user(userId)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"COMPLETED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
		mvc.perform(put("/api/v2/support-plan-occurrences/{id}/state", resource).with(user(userId)).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"SCHEDULED\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OCCURRENCE_NOT_OPEN"));
		String tomorrow = values.stream().filter(value -> today.plusDays(1).toString().equals(value.path("localDate").asText()))
				.findFirst().orElseThrow().path("occurrenceId").asText();
		mvc.perform(put("/api/v2/support-plan-occurrences/{id}/state", tomorrow).with(user(userId)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"SKIPPED\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OCCURRENCE_NOT_DUE"));
		assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id=:id and message_type='care.support-plan.action-occurrence-changed'")
				.param("id", UUID.fromString(resource)).query(Long.class).single()).isEqualTo(1);
		var events = jdbc.sql("select payload::text from outbox_event where aggregate_id=:id and message_type='care.support-plan.action-occurrence-changed'")
				.param("id", UUID.fromString(emotion)).query(String.class).list();
		assertThat(events).hasSize(2).allSatisfy(event -> {
			assertThat(event).contains("STATE_CHANGED").containsPattern("\"ownerFact\"\\s*:\\s*null");
			assertThat(event).doesNotContain("privateNote", "intensity", "emotionValue", "diagnosis");
		});
	}

	private int activateAfter(CountDownLatch start, UUID user, UUID plan, String key) throws Exception {
		start.await(5, TimeUnit.SECONDS);
		return mvc.perform(post("/api/v2/support-plans/{id}/activate", plan).with(user(user)).header("If-Match", "\"0\"")
				.header("Idempotency-Key", key)).andReturn().getResponse().getStatus();
	}
	private JsonNode activate(UUID user, UUID plan, long version, String key) throws Exception {
		return response(mvc.perform(post("/api/v2/support-plans/{id}/activate", plan).with(user(user)).header("If-Match", '"' + Long.toString(version) + '"')
				.header("Idempotency-Key", key)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}
	private JsonNode propose(UUID user, UUID evaluation, String key) throws Exception {
		return response(mvc.perform(post("/api/v2/support-plans").with(user(user)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content("{\"sourceSupportEvaluationId\":\"" + evaluation + "\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}
	private void preferences(UUID user, String mode, int budget, String pace, long version) throws Exception {
		mvc.perform(put("/api/v2/support-plan-personalization").with(user(user)).header("If-Match", '"' + Long.toString(version) + '"')
				.contentType(MediaType.APPLICATION_JSON).content("{\"preferredActivityModes\":[\"" + mode + "\"],\"commonBarriers\":[],\"dailyTimeBudgetMinutes\":"
						+ budget + ",\"desiredPace\":\"" + pace + "\"}"))
				.andExpect(status().isOk());
	}
	private List<JsonNode> actions(JsonNode plan) {
		return java.util.stream.StreamSupport.stream(plan.path("focuses").spliterator(), false).flatMap(focus ->
				java.util.stream.StreamSupport.stream(focus.path("actions").spliterator(), false)).toList();
	}
	private JsonNode action(JsonNode plan, String type) { return actions(plan).stream().filter(value -> type.equals(value.path("actionType").asText())).findFirst().orElseThrow(); }
	private JsonNode response(String json) throws Exception { return mapper.readTree(json); }
	private RequestPostProcessor user(UUID id) { return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER")); }
	private long rows(String table, UUID planId) { return jdbc.sql("select count(*) from " + table + " where support_plan_id=:id").param("id", planId).query(Long.class).single(); }
	private UUID profile() {
		var id = UUID.randomUUID();
		jdbc.sql("insert into user_profile(account_id,display_name) values(:id,'Native plan test user')").param("id", id).update();
		return id;
	}
	private CurrentEntitlementResponse paid(UUID user, ServicePackage value) { return new CurrentEntitlementResponse(user, value, value == ServicePackage.FREE ? EntitlementSource.DEFAULT_FREE : EntitlementSource.DEMO,
			"test-native", Instant.now().minusSeconds(86400), Instant.now().plusSeconds(86400), "service-entitlement-v1", 1, Instant.now()); }
	private void eligible(ResourceEligibilityOutcome outcome, ResourceEligibilityReasonCode reason) {
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			var request = call.getArgument(0, ResourceEligibilityBatchRequest.class);
			var results = request.requests().stream().map(query -> new ResourceEligibilityResult(query.requestId(), query.resourceId(), query.contentVersion(), outcome, reason,
					outcome == ResourceEligibilityOutcome.ELIGIBLE ? query.requiredRole().name().equals("PRIMARY") ? EligibilityRole.PRIMARY : EligibilityRole.ADJUNCT : null,
					outcome == ResourceEligibilityOutcome.ELIGIBLE ? UUID.nameUUIDFromBytes(query.resourceId().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString() : null,
					outcome == ResourceEligibilityOutcome.ELIGIBLE ? ResourceCategory.ARTICLE : null, outcome == ResourceEligibilityOutcome.ELIGIBLE ? "Reviewed resource" : null,
					outcome == ResourceEligibilityOutcome.ELIGIBLE ? "Reviewed resource summary" : null, null)).toList();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", Instant.now().toString(), results);
		}).when(eligibility).resolve(any(), anyString(), any());
	}
	private UUID evaluation(UUID user, boolean professional) throws Exception {
		UUID phq = assessment(user, "10000000-0000-0000-0000-000000000004", false, professional);
		UUID gad = assessment(user, "10000000-0000-0000-0000-000000000003", true, false);
		var value = response(mvc.perform(post("/api/v2/support-evaluations").with(user(user)).header("Idempotency-Key", "native-evaluation-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON).content("{\"phq9AssessmentId\":\"" + phq + "\",\"gad7AssessmentId\":\"" + gad + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		UUID id = UUID.fromString(value.path("supportEvaluationId").asText());
		jdbc.sql("""
				insert into screening_episode(id,user_id,purpose,status,phq9_assessment_id,gad7_assessment_id,support_evaluation_id,created_at,updated_at,completed_at,version)
				values(:id,:user,'INITIAL_CHECK','COMPLETED',:phq,:gad,:evaluation,now(),now(),now(),0)
				""").param("id", UUID.randomUUID()).param("user", user).param("phq", phq).param("gad", gad).param("evaluation", id).update();
		return id;
	}
	private UUID assessment(UUID user, String definition, boolean gad, boolean professional) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into assessment_submission(id,user_id,definition_id,idempotency_key,request_hash,privacy_policy_version,submitted_at)
				values(:id,:user,:definition,:key,:hash,'privacy-capstone-v3',now())
				""").param("id", id).param("user", user).param("definition", UUID.fromString(definition)).param("key", "native-assessment-" + id).param("hash", "0".repeat(64)).update();
		jdbc.sql("""
				insert into assessment_result(submission_id,total_score,screening_level,scoring_version,safety_item_positive,safety_status,safety_policy_version,disclaimer_code,calculated_at)
				values(:id,:score,:level,:scoring,:positive,:status,:policy,'SCREENING_NOT_DIAGNOSIS',now())
				""").param("id", id).param("score", professional ? 12 : 3).param("level", professional ? "MODERATE" : "MINIMAL")
				.param("scoring", gad ? "gad7-standard-bands-v1" : "phq9-standard-bands-v1").param("positive", gad ? null : false)
				.param("status", gad ? "NOT_APPLICABLE" : "NEGATIVE_SAFETY_SCREEN").param("policy", gad ? null : "MB-SAFETY-PHQ9-001-v1").update();
		return id;
	}

	private UUID summary(UUID user, UUID draftId) throws Exception {
		UUID id = UUID.randomUUID();
		UUID job = UUID.randomUUID();
		Instant end = Instant.now().minusSeconds(1);
		Instant currentStart = end.minusSeconds(14L * 86400);
		Instant previousStart = currentStart.minusSeconds(14L * 86400);
		var assessmentIds = jdbc.sql("""
				select evaluation.phq9_assessment_id,evaluation.gad7_assessment_id from support_plan plan
				join support_evaluation_v2 evaluation on evaluation.id=plan.support_evaluation_id where plan.id=:id
				""").param("id", draftId).query((row, index) -> List.of(row.getObject(1, UUID.class), row.getObject(2, UUID.class))).single();
		var trends = java.util.stream.IntStream.range(0, 2).mapToObj(index -> new ReassessmentSummaryView.ScreeningTrend(
				index == 0 ? "PHQ9" : "GAD7", "INSUFFICIENT_DATA", "test-v1", null,
				new ReassessmentSummaryView.ScreeningPoint(assessmentIds.get(index), "test-v1", end, 0,
						com.mentalbridge.care.assessment.ScreeningLevel.MINIMAL), null, "INSUFFICIENT_DATA")).toList();
		var summary = new ReassessmentSummaryView(id, "reassessment-summary-v2", end,
				new ReassessmentSummaryView.Period(previousStart, currentStart), new ReassessmentSummaryView.Period(currentStart, end),
				new ReassessmentSummaryView.ScreeningDimension("INSUFFICIENT_DATA", trends),
				new ReassessmentSummaryView.JournalDimension("UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", job, null,
						List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null),
				new ReassessmentSummaryView.EngagementDimension("INSUFFICIENT_DATA", new ReassessmentSummaryView.EngagementPeriod(0, 0),
						new ReassessmentSummaryView.EngagementPeriod(0, 0), List.of()),
				new ReassessmentSummaryView.SelfReportedExperienceDimension("INSUFFICIENT_DATA", "NOT_PROVIDED", null),
				new ReassessmentSummaryView.ReflectionDimension("INSUFFICIENT_DATA", List.of()), null, "FOUR_DIMENSIONS_NOT_COMBINED");
		jdbc.sql("""
				insert into reassessment_summary(id,user_id,idempotency_key,request_hash,summary_version,journal_job_id,
				previous_period_start,previous_period_end,current_period_start,current_period_end,snapshot,composed_at)
				values(:id,:user,:key,:hash,'reassessment-summary-v2',:job,:previous,:current,:current,:end,cast(:snapshot as jsonb),:end)
				""").param("id", id).param("user", user).param("key", "native-summary-" + id).param("hash", "1".repeat(64))
				.param("job", job).param("previous", java.sql.Timestamp.from(previousStart)).param("current", java.sql.Timestamp.from(currentStart))
				.param("end", java.sql.Timestamp.from(end)).param("snapshot", mapper.writeValueAsString(summary)).update();
		return id;
	}
}
