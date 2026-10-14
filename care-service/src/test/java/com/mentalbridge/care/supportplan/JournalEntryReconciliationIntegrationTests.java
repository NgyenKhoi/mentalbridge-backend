package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.mentalbridge.care.supportplan.JournalEntryEvidenceHttpClient.Evidence;
import com.mentalbridge.care.supportplan.JournalEntryEvidenceHttpClient.Fact;
import com.mentalbridge.care.supportplan.PlanActionEntity.Presentation;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JournalEntryReconciliationIntegrationTests extends CareTestProperties {
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper mapper;
	@Autowired SupportPlanRepository plans;
	@Autowired SupportPlanFocusRepository focuses;
	@Autowired PlanActionRepository actions;
	@Autowired SupportPlanActivityScheduleRepository schedules;
	@MockitoBean JournalEntryEvidenceClient evidence;

	@Test
	void reconcilesWithNoRemoteTransactionAndPreservesReplayAndDeletedHistory() throws Exception {
		var target = target(1);
		var occurredAt = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
		var fact = new Fact("JOURNAL_AI", "JOURNAL_ENTRY", target.journalId(), "1", occurredAt);
		when(evidence.read(any(), any(), anyString(), any())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(invocation.getArgument(0, UUID.class)).isEqualTo(target.userId());
			assertThat(invocation.getArgument(1, UUID.class)).isEqualTo(target.journalId());
			return new Evidence("journal-entry-completion-evidence-v1", target.userId(), target.journalId(),
					"RECORDED", fact, Instant.now().truncatedTo(ChronoUnit.MICROS));
		});
		var first = mvc.perform(command(target.occurrenceIds().get(0), target.journalId()).with(user(target.userId())))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.sourceState").value("RECORDED"))
				.andExpect(jsonPath("$.occurrenceState").value("COMPLETED"))
				.andExpect(jsonPath("$.completionEvidence.factId").value(fact.factId().toString()))
				.andReturn().getResponse().getContentAsString();
		var replay = mvc.perform(command(target.occurrenceIds().get(0), target.journalId()).with(user(target.userId())))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(first));
		doReturn(new Evidence("journal-entry-completion-evidence-v1", target.userId(), target.journalId(),
				"DELETED", null, Instant.now())).when(evidence).read(any(), any(), anyString(), any());
		var deleted = mvc.perform(command(target.occurrenceIds().get(0), target.journalId()).with(user(target.userId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.sourceState").value("DELETED"))
				.andExpect(jsonPath("$.occurrenceState").value("COMPLETED"))
				.andReturn().getResponse().getContentAsString();
		assertThat(mapper.readTree(deleted).path("completionEvidence")).isEqualTo(mapper.readTree(first).path("completionEvidence"));
		assertThat(outboxCount(target.occurrenceIds().get(0))).isEqualTo(1);
		assertThat(first).doesNotContain("emotionValue", "intensity", "factors", "note", "score", "diagnosis");
	}

	@Test
	void enforcesUserRoleAndOpaqueOwnershipAndNeverTreatsOutageAsCompletion() throws Exception {
		var target = target(1);
		mvc.perform(command(target.occurrenceIds().get(0), target.journalId())).andExpect(status().isUnauthorized());
		mvc.perform(command(target.occurrenceIds().get(0), target.journalId()).with(jwt().jwt(token -> token.subject(target.userId().toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST")))).andExpect(status().isForbidden());
		mvc.perform(command(target.occurrenceIds().get(0), target.journalId()).with(user(UUID.randomUUID()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("OCCURRENCE_NOT_FOUND"));
		when(evidence.read(any(), any(), anyString(), any())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
				"JOURNAL_ENTRY_EVIDENCE_UNAVAILABLE", "Owner unavailable"));
		mvc.perform(command(target.occurrenceIds().get(0), target.journalId()).with(user(target.userId()))).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("JOURNAL_ENTRY_EVIDENCE_UNAVAILABLE"));
		assertThat(occurrenceState(target.occurrenceIds().get(0))).isEqualTo("SCHEDULED");
		assertThat(outboxCount(target.occurrenceIds().get(0))).isZero();
	}

	@Test
	void concurrentRequestsCannotUseTheSameEntryToCompleteTwoActions() throws Exception {
		var target = target(2);
		var fact = new Fact("JOURNAL_AI", "JOURNAL_ENTRY", target.journalId(), "1",
				Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS));
		when(evidence.read(any(), any(), anyString(), any())).thenAnswer(invocation -> new Evidence(
				"journal-entry-completion-evidence-v1", target.userId(), target.journalId(), "RECORDED", fact, Instant.now()));
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> reconcileAfter(start, target, 0));
			var second = executor.submit(() -> reconcileAfter(start, target, 1));
			start.countDown();
			assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(200, 409);
		}
		assertThat(target.occurrenceIds().stream().filter(id -> "COMPLETED".equals(occurrenceState(id))).count()).isEqualTo(1);
		assertThat(target.occurrenceIds().stream().mapToLong(this::outboxCount).sum()).isEqualTo(1);
	}

	@Test
	void recordedWritingDateMismatchAndOwnerAbsenceDoNotWriteCompletion() throws Exception {
		var target = target(1);
		var fact = new Fact("JOURNAL_AI", "JOURNAL_ENTRY", target.journalId(), "1",
				Instant.now().minusSeconds(86400).truncatedTo(ChronoUnit.MICROS));
		when(evidence.read(any(), any(), anyString(), any())).thenReturn(new Evidence(
				"journal-entry-completion-evidence-v1", target.userId(), target.journalId(), "RECORDED", fact, Instant.now()));
		mvc.perform(command(target.occurrenceIds().get(0), target.journalId()).with(user(target.userId())))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOURNAL_ENTRY_EVIDENCE_MISMATCH"));
		for (String state : List.of("MISSING", "DELETED")) {
			when(evidence.read(any(), any(), anyString(), any())).thenReturn(new Evidence(
					"journal-entry-completion-evidence-v1", target.userId(), target.journalId(), state, null, Instant.now()));
			mvc.perform(command(target.occurrenceIds().get(0), target.journalId()).with(user(target.userId())))
					.andExpect(status().isOk()).andExpect(jsonPath("$.sourceState").value(state))
					.andExpect(jsonPath("$.occurrenceState").value("SCHEDULED"))
					.andExpect(jsonPath("$.completionEvidence").isEmpty());
		}
		assertThat(outboxCount(target.occurrenceIds().get(0))).isZero();
	}

	@Test
	void rejectsMissingNullInvalidAndExtraCandidateFieldsBeforeConsultingTheOwner() throws Exception {
		var target = target(1);
		for (String body : List.of("{}", "{\"journalId\":null}", "{\"journalId\":\"invalid\"}",
				"{\"journalId\":\"" + target.journalId() + "\",\"other\":\"" + UUID.randomUUID() + "\"}")) {
			mvc.perform(post(path(target.occurrenceIds().get(0))).with(user(target.userId()))
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		}
		org.mockito.Mockito.verifyNoInteractions(evidence);
		assertThat(occurrenceState(target.occurrenceIds().get(0))).isEqualTo("SCHEDULED");
	}

	private int reconcileAfter(CountDownLatch start, Target target, int index) throws Exception {
		start.await(5, TimeUnit.SECONDS);
		return mvc.perform(command(target.occurrenceIds().get(index), target.journalId()).with(user(target.userId())))
				.andReturn().getResponse().getStatus();
	}

	private Target target(int count) throws Exception {
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
					"JOURNAL", "NOTICE_EMOTIONS", null, new SourceReference("JOURNAL_AI",
							"JOURNAL_TEMPLATE", "short-reflection", "1", "journal-writing-intent-v1",
							null, null, activatedAt), new Provenance("USER_SELECTED", null, null, activatedAt),
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
		return new Target(userId, date, ids, UUID.randomUUID());
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

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder command(UUID occurrenceId, UUID journalId) {
		return post(path(occurrenceId)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"journalId\":\"" + journalId + "\"}");
	}

	private String path(UUID occurrenceId) {
		return "/api/v2/support-plan-occurrences/" + occurrenceId + "/journal/reconcile";
	}

	private RequestPostProcessor user(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private record Target(UUID userId, LocalDate date, List<UUID> occurrenceIds, UUID journalId) { }
}
