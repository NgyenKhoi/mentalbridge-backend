package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.ResourceEvidenceHttpClient.Evidence;

import com.mentalbridge.care.supportplan.PlanActionEntity.Presentation;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ResourceReconciliationIntegrationTests extends CareTestProperties {
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper mapper;
	@Autowired SupportPlanRepository plans;
	@Autowired SupportPlanFocusRepository focuses;
	@Autowired PlanActionRepository actions;
	@Autowired SupportPlanActivityScheduleRepository schedules;
	@Autowired SupportPlanActivityOccurrenceRepository occurrences;
	@MockitoBean ResourceEvidenceClient evidence;

	@Test
	void exactFactCompletesOnceWithoutRemoteTransactionAndCannotBeReopened() throws Exception {
		var target = target(1, UUID.randomUUID());
		stub(target);
		var first = reconcile(target, 0, "0").andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.state").value("COMPLETED"))
				.andExpect(jsonPath("$.completionEvidence.ownerDomain").value("CONTENT"))
				.andReturn().getResponse().getContentAsString();
		var replay = reconcile(target, 0, "0").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(first));
		assertThat(occurrences.findUsedResourceFactIds(target.userId(), target.resourceId().toString(), "7",
				target.occurrenceIds().getFirst())).isEmpty();
		assertThat(outboxCount(target.occurrenceIds().getFirst())).isEqualTo(1);
		mvc.perform(put("/api/v2/support-plan-occurrences/" + target.occurrenceIds().getFirst() + "/state")
				.with(user(target.userId())).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"SCHEDULED\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OCCURRENCE_NOT_OPEN"));
	}

	@Test
	void missingWrongDateWrongVersionAndContentOutageLeaveOccurrenceUnchanged() throws Exception {
		var target = target(1, UUID.randomUUID());
		for (Evidence fact : new Evidence[] { null, new Evidence("RESOURCE_COMPLETION", factId(target), "7",
				Instant.now().minusSeconds(86400), Instant.now()), new Evidence("RESOURCE_COMPLETION", factId(target), "8",
				Instant.now().minusSeconds(2), Instant.now()) }) {
			when(evidence.read(any(), anyString(), any(), anyString(), anyList(), anyString(), any())).thenReturn(fact);
			reconcile(target, 0, "0").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_EVIDENCE_NOT_FOUND"));
		}
		when(evidence.read(any(), anyString(), any(), anyString(), anyList(), anyString(), any())).thenThrow(
				new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RESOURCE_EVIDENCE_UNAVAILABLE", "Unavailable"));
		reconcile(target, 0, "0").andExpect(status().isServiceUnavailable());
		assertThat(occurrenceState(target.occurrenceIds().getFirst())).isEqualTo("SCHEDULED");
		assertThat(outboxCount(target.occurrenceIds().getFirst())).isZero();
	}

	@Test
	void ownerRoleVersionAndPausedPlanAreCheckedBeforeContent() throws Exception {
		var target = target(1, UUID.randomUUID());
		String path = path(target.occurrenceIds().getFirst());
		mvc.perform(post(path).header("If-Match", "\"0\"")).andExpect(status().isUnauthorized());
		mvc.perform(post(path).with(jwt().jwt(token -> token.subject(target.userId().toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))).header("If-Match", "\"0\""))
				.andExpect(status().isForbidden());
		mvc.perform(post(path).with(user(UUID.randomUUID())).header("If-Match", "\"0\""))
				.andExpect(status().isNotFound());
		reconcile(target, 0, "99").andExpect(status().isPreconditionFailed());
		mvc.perform(post(path).with(user(target.userId()))).andExpect(status().isBadRequest());
		mvc.perform(post(path).with(user(target.userId())).header("If-Match", "bad")).andExpect(status().isBadRequest());
		jdbc.sql("update support_plan set status='PAUSED' where id=:id").param("id", target.planId()).update();
		reconcile(target, 0, "0").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_NOT_ACTIVE"));
		verifyNoInteractions(evidence);
	}

	@Test
	void oneOwnerCannotReuseFactButDifferentOwnersCanUseTheirOwnSameResourceDailyRow() throws Exception {
		UUID resourceId = UUID.randomUUID();
		var first = target(2, resourceId);
		var otherOwner = target(1, resourceId);
		stub(first);
		reconcile(first, 0, "0").andExpect(status().isOk());
		reconcile(first, 1, "0").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_EVIDENCE_ALREADY_USED"));
		stub(otherOwner);
		reconcile(otherOwner, 0, "0").andExpect(status().isOk());
		assertThat(occurrenceState(first.occurrenceIds().get(1))).isEqualTo("SCHEDULED");
	}

	@Test
	void excludesUsedFactsAndAcceptsAnotherPracticeWithoutChangingExistingEvidence() throws Exception {
		var target = target(3, UUID.randomUUID());
		String firstFact = UUID.randomUUID().toString();
		String secondFact = UUID.randomUUID().toString();
		org.mockito.Mockito.doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			List<String> excluded = invocation.getArgument(4);
			if (excluded.containsAll(List.of(firstFact, secondFact))) return null;
			String selected = excluded.contains(firstFact) ? secondFact : firstFact;
			return new Evidence("RESOURCE_PRACTICE_SESSION", selected, "7", Instant.now().minusSeconds(2), Instant.now());
		}).when(evidence).read(any(), anyString(), any(), anyString(), anyList(), anyString(), any());
		reconcile(target, 0, "0").andExpect(status().isOk())
				.andExpect(jsonPath("$.completionEvidence.factId").value(firstFact));
		reconcile(target, 1, "0").andExpect(status().isOk())
				.andExpect(jsonPath("$.completionEvidence.factId").value(secondFact));
		reconcile(target, 2, "0").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RESOURCE_EVIDENCE_ALREADY_USED"));
		assertThat(occurrenceState(target.occurrenceIds().get(2))).isEqualTo("SCHEDULED");
		assertThat(outboxCount(target.occurrenceIds().get(2))).isZero();
		org.mockito.Mockito.verify(evidence, org.mockito.Mockito.times(2)).read(any(), anyString(), any(), anyString(),
				org.mockito.ArgumentMatchers.eq(List.of()), anyString(), any());
	}

	@Test
	void simultaneousResourceFactReuseCreatesOnlyOneCompletion() throws Exception {
		var target = target(2, UUID.randomUUID());
		stub(target);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> { start.await(); return reconcile(target, 0, "0").andReturn().getResponse().getStatus(); });
			var second = executor.submit(() -> { start.await(); return reconcile(target, 1, "0").andReturn().getResponse().getStatus(); });
			start.countDown();
			assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
		}
	}

	@Test
	void actionAndOccurrenceChangesDuringContentReadRejectTheStaleContext() throws Exception {
		for (String change : List.of(
				"update support_plan_action set version=version+1 where id=(select source_action_id from support_plan_activity_occurrence where id=:id)",
				"update support_plan_activity_occurrence set version=version+1 where id=:id")) {
			var target = target(1, UUID.randomUUID());
			org.mockito.Mockito.doAnswer(invocation -> {
				assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
				jdbc.sql(change).param("id", target.occurrenceIds().getFirst()).update();
				return new Evidence("RESOURCE_COMPLETION", factId(target), "7", Instant.now().minusSeconds(2), Instant.now());
			}).when(evidence).read(any(), anyString(), any(), anyString(), anyList(), anyString(), any());
			reconcile(target, 0, "0").andExpect(status().isPreconditionFailed())
					.andExpect(jsonPath("$.code").value("OCCURRENCE_VERSION_MISMATCH"));
			assertThat(occurrenceState(target.occurrenceIds().getFirst())).isEqualTo("SCHEDULED");
			assertThat(outboxCount(target.occurrenceIds().getFirst())).isZero();
		}
	}

	@Test
	void planPausedDuringContentReadRejectsCompletion() throws Exception {
		var target = target(1, UUID.randomUUID());
		org.mockito.Mockito.doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			jdbc.sql("update support_plan set status='PAUSED' where id=:id").param("id", target.planId()).update();
			return new Evidence("RESOURCE_COMPLETION", factId(target), "7", Instant.now().minusSeconds(2), Instant.now());
		}).when(evidence).read(any(), anyString(), any(), anyString(), anyList(), anyString(), any());
		reconcile(target, 0, "0").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_NOT_ACTIVE"));
		assertThat(occurrenceState(target.occurrenceIds().getFirst())).isEqualTo("SCHEDULED");
		assertThat(outboxCount(target.occurrenceIds().getFirst())).isZero();
	}

	@Test
	void migrationRejectsContentEvidenceForWrongActionAndAcceptsPracticeFactKind() throws Exception {
		var target = target(1, UUID.randomUUID());
		when(evidence.read(any(), anyString(), any(), anyString(), anyList(), anyString(), any())).thenReturn(
				new Evidence("RESOURCE_PRACTICE_SESSION", UUID.randomUUID().toString(), "7", Instant.now().minusSeconds(2), Instant.now()));
		reconcile(target, 0, "0").andExpect(status().isOk());
		assertThatThrownBy(() -> jdbc.sql("update support_plan_activity_occurrence set evidence_kind='JOURNAL_ENTRY' where id=:id")
				.param("id", target.occurrenceIds().getFirst()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void projectionIsMinimizedAndUsesNativeResourceActionsAndNoneIsSuccessful() throws Exception {
		var target = target(1, UUID.randomUUID());
		var content = mvc.perform(get("/api/v2/support-plans/current/resource-actions").with(user(target.userId()))
				.param("localDate", target.date().toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.planState").value("ACTIVE"))
				.andExpect(jsonPath("$.timeZone").value("Asia/Ho_Chi_Minh"))
				.andExpect(jsonPath("$.actions[0].resource.resourceVersion").value("7"))
				.andExpect(jsonPath("$.actions[0].today.occurrenceVersion").value(0))
				.andExpect(jsonPath("$.actions[0].today.timeZone").value("Asia/Ho_Chi_Minh"))
				.andReturn().getResponse().getContentAsString();
		assertThat(content).doesNotContain("rationale", "purposeCode", "instructions", "summary", "Safety guidance");
		mvc.perform(get("/api/v2/support-plans/current/resource-actions").with(user(UUID.randomUUID()))
				.param("localDate", target.date().toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.planState").value("NONE")).andExpect(jsonPath("$.actions").isEmpty());
		jdbc.sql("update support_plan set status='PAUSED' where id=:id").param("id", target.planId()).update();
		mvc.perform(get("/api/v2/support-plans/current/resource-actions").with(user(target.userId()))
				.param("localDate", target.date().toString())).andExpect(status().isOk())
				.andExpect(jsonPath("$.planState").value("PAUSED")).andExpect(jsonPath("$.actions.length()").value(1));
	}

	private void stub(Target target) {
		org.mockito.Mockito.doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(invocation.getArgument(0, UUID.class)).isEqualTo(target.resourceId());
			assertThat(invocation.getArgument(1, String.class)).isEqualTo("7");
			assertThat(invocation.getArgument(2, LocalDate.class)).isEqualTo(target.date());
			return new Evidence("RESOURCE_COMPLETION", factId(target), "7", Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS), Instant.now().truncatedTo(ChronoUnit.MICROS));
		}).when(evidence).read(any(), anyString(), any(), anyString(), anyList(), anyString(), any());
	}
	private String factId(Target target) { return target.resourceId() + ":" + target.date(); }
	private org.springframework.test.web.servlet.ResultActions reconcile(Target target, int index, String version) throws Exception {
		return mvc.perform(post(path(target.occurrenceIds().get(index))).with(user(target.userId())).header("If-Match", "\"" + version + "\""));
	}
	private Target target(int count, UUID resourceId) throws Exception {
		UUID userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id,display_name) values (:id,'Emotion action test user')")
				.param("id", userId).update();
		UUID phq = assessment(userId, "10000000-0000-0000-0000-000000000004", false);
		UUID gad = assessment(userId, "10000000-0000-0000-0000-000000000003", true);
		String evaluation = mvc.perform(post("/api/v2/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "emotion-evaluation-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"phq9AssessmentId\":\"" + phq + "\",\"gad7AssessmentId\":\"" + gad + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID evaluationId = UUID.fromString(mapper.readTree(evaluation).path("supportEvaluationId").asText());
		Instant activatedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
		LocalDate date = activatedAt.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
		var plan = SupportPlanEntity.nativeDraft(UUID.randomUUID(), userId, evaluationId,
				"mb-support-routing-capstone-v2", activatedAt, "support-plan-v3-actions-v1", "PLUS", "DEMO",
				"service-entitlement-v1", 1, activatedAt, "DOMAIN_AWARE_WELLBEING_SUPPORT", "Plan rationale",
				"NEGATIVE_SAFETY_SCREEN", "NO_ITEM9_SIGNAL", "safety-v1", "STANDARD_SAFETY_GUIDANCE", "Safety guidance",
				count, activatedAt);
		plan = plans.saveAndFlush(plan);
		var focus = focuses.saveAndFlush(new SupportPlanFocusEntity(UUID.randomUUID(), plan.id(), userId, 1,
				"Notice emotions", null, activatedAt));
		plan.activate(activatedAt);
		plan = plans.saveAndFlush(plan);
		for (int ordinal = 1; ordinal <= count; ordinal++) {
			var action = new PlanActionEntity(UUID.randomUUID(), plan.id(), focus.id(), userId, ordinal,
					"RESOURCE", "BUILD_ROUTINE", null, new SourceReference("CONTENT",
							"RESOURCE_VERSION", resourceId.toString(), "7", null,
							UUID.randomUUID(), "resource-eligibility-v1", activatedAt), new Provenance("USER_SELECTED", null, null, activatedAt),
					new Presentation("Emotion check-in", null, null, null), activatedAt);
			action = actions.saveAndFlush(action);
			action.activate(activatedAt);
			action = actions.saveAndFlush(action);
			schedules.saveAndFlush(new SupportPlanActivityScheduleEntity(UUID.randomUUID(), plan.id(), userId,
					ordinal, plan.version(), action, "support-plan-activity-schedule-v2", "DAILY", null,
					LocalTime.of(9, 0), "Asia/Ho_Chi_Minh", date, true, activatedAt));
		}
		String response = mvc.perform(get("/api/v2/support-plan-occurrences").with(user(userId))
				.param("from", date.toString()).param("through", date.toString())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		var ids = java.util.stream.StreamSupport.stream(mapper.readTree(response).path("occurrences").spliterator(), false)
				.map(value -> UUID.fromString(value.path("occurrenceId").asText())).toList();
		assertThat(ids).hasSize(count);
		return new Target(userId, plan.id(), date, ids, resourceId);
	}

	private UUID assessment(UUID userId, String definition, boolean gad) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into assessment_submission
				(id,user_id,definition_id,idempotency_key,request_hash,privacy_policy_version,submitted_at)
				values (:id,:user,:definition,:key,:hash,'privacy-capstone-v3',now())
				""").param("id", id).param("user", userId).param("definition", UUID.fromString(definition))
				.param("key", "emotion-assessment-" + id).param("hash", "0".repeat(64)).update();
		jdbc.sql("""
				insert into assessment_result
				(submission_id,total_score,screening_level,scoring_version,safety_item_positive,safety_status,
				 safety_policy_version,disclaimer_code,calculated_at)
				values (:id,3,'MINIMAL',:scoring,:positive,:status,:policy,'SCREENING_NOT_DIAGNOSIS',now())
				""").param("id", id).param("scoring", gad ? "gad7-standard-bands-v1" : "phq9-standard-bands-v1")
				.param("positive", gad ? null : false).param("status", gad ? "NOT_APPLICABLE" : "NEGATIVE_SAFETY_SCREEN")
				.param("policy", gad ? null : "MB-SAFETY-PHQ9-001-v1").update();
		return id;
	}

	private String occurrenceState(UUID id) {
		return jdbc.sql("select state from support_plan_activity_occurrence where id=:id")
				.param("id", id).query(String.class).single();
	}

	private long outboxCount(UUID id) {
		return jdbc.sql("select count(*) from outbox_event where aggregate_id=:id and message_type='care.support-plan.action-occurrence-changed'")
				.param("id", id).query(Long.class).single();
	}

	private String path(UUID occurrenceId) {
		return "/api/v2/support-plan-occurrences/" + occurrenceId + "/resource/reconcile";
	}

	private RequestPostProcessor user(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private record Target(UUID userId, UUID planId, LocalDate date, List<UUID> occurrenceIds, UUID resourceId) { }
}
