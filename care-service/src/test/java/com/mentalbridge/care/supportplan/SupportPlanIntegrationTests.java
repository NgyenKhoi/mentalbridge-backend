package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
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
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.ConsultationResourceProposalHttpClient.ResourceProposal;
import com.mentalbridge.care.supportplan.PlanActionEntity.Presentation;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.AuthoritativeOwnerFact;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.LinkedActionSource;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.OwnerFact;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SupportPlanIntegrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper objectMapper;
	@Autowired SupportPlanRepository plans;
	@Autowired SupportPlanFocusRepository focuses;
	@Autowired PlanActionRepository actions;
	@Autowired SupportPlanActivityScheduleRepository schedules;
	@Autowired SupportPlanActivityOccurrenceService activityOccurrences;
	@MockitoBean EntitlementClient entitlements;
	@MockitoBean ResourceEligibilityClient eligibility;
	@MockitoBean ConsultationResourceProposalClient resourceProposals;

	@BeforeEach
	void dependencies() {
		when(entitlements.current(any(), anyString(), any())).thenAnswer(invocation -> paid(invocation.getArgument(0)));
		eligible(ResourceEligibilityOutcome.ELIGIBLE, ResourceEligibilityReasonCode.ELIGIBLE_MATCH);
	}

	@Test
	void storesVersionedPersonalizationWithoutLeakingItToAnotherActor() throws Exception {
		var userId = insertProfile();
		var otherUser = insertProfile();

		mvc.perform(get("/api/v2/support-plan-personalization").with(user(userId)))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
				.andExpect(jsonPath("$.status").value("NOT_PROVIDED"))
				.andExpect(jsonPath("$.planReview.required").value(false));

		String request = """
				{"preferredFocus":"SLEEP","desiredPace":"GENTLE","dailyTimeBudgetMinutes":10,
				 "preferredDaypart":"EVENING","preferredActivityModes":["READING","JOURNALING"],
				 "commonBarriers":["LOW_ENERGY"]}
				""";
		mvc.perform(put("/api/v2/support-plan-personalization").with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.schemaVersion").value("support-plan-personalization-v1"))
				.andExpect(jsonPath("$.revision").value(1))
				.andExpect(jsonPath("$.preferredFocus").value("SLEEP"))
				.andExpect(jsonPath("$.preferredActivityModes[1]").value("JOURNALING"));

		mvc.perform(put("/api/v2/support-plan-personalization").with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_PERSONALIZATION_VERSION_MISMATCH"));
		mvc.perform(put("/api/v2/support-plan-personalization").with(user(userId))
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"preferredActivityModes\":[null],\"commonBarriers\":[]}"))
				.andExpect(status().isBadRequest());

		mvc.perform(get("/api/v2/support-plan-personalization").with(user(otherUser)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_PROVIDED"));
		mvc.perform(get("/api/v2/support-plan-personalization")
				.with(jwt().jwt(token -> token.subject(userId.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isForbidden());

		assertThat(jdbc.sql("select count(*) from support_plan_personalization where user_id = :userId")
				.param("userId", userId).query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_personalization_activity_mode")
				.query(Integer.class).single()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void snapshotsPartialPreferencesAndRequiresReviewWithoutMutatingTheActivePlan() throws Exception {
		var userId = insertProfile();
		var evaluationId = evaluation(userId, "MINIMAL", "MINIMAL", false);
		String eveningPreference = """
				{"preferredFocus":"SLEEP","preferredDaypart":"EVENING",
				 "preferredActivityModes":[],"commonBarriers":[]}
				""";
		var saved = mvc.perform(put("/api/v2/support-plan-personalization").with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content(eveningPreference))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.status").value("PROVIDED"))
				.andReturn().getResponse().getContentAsString();
		var personalizationId = UUID.fromString(objectMapper.readTree(saved).path("personalizationId").asText());

		var created = createPlan(userId, evaluationId, "personalized-plan-create");
		var planId = UUID.fromString(created.path("supportPlanId").asText());
		mvc.perform(get("/api/v2/support-plans/{id}", planId).with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.focuses[0].title").value("Xây dựng nhịp ngủ dễ chịu"))
				.andExpect(jsonPath("$.focuses[0].personalizationProvenance.personalizationId")
						.value(personalizationId.toString()))
				.andExpect(jsonPath("$.focuses[0].personalizationProvenance.revision").value(1));
		assertThat(jdbc.sql("select personalization_revision from support_plan where id=:id")
				.param("id", planId).query(Long.class).single()).isEqualTo(1L);

		mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "personalized-plan-activate"))
				.andExpect(status().isOk());
		assertThat(jdbc.sql("""
				select to_char(local_time, 'HH24:MI') from support_plan_activity_schedule
				where support_plan_id=:id and ordinal=1
				""").param("id", planId).query(String.class).single()).isEqualTo("18:00");

		String morningPreference = """
				{"preferredFocus":"MOTIVATION","preferredDaypart":"MORNING",
				 "preferredActivityModes":[],"commonBarriers":["DIFFICULT_TO_START"]}
				""";
		mvc.perform(put("/api/v2/support-plan-personalization").with(user(userId))
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content(morningPreference))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.planReview.required").value(true))
				.andExpect(jsonPath("$.planReview.supportPlanId").value(planId.toString()))
				.andExpect(jsonPath("$.planReview.reasonCode")
						.value("PREFERENCES_CHANGED_AFTER_PLAN_ACTIVATION"));

		assertThat(jdbc.sql("select personalization_revision from support_plan where id=:id")
				.param("id", planId).query(Long.class).single()).isEqualTo(1L);
		assertThat(jdbc.sql("""
				select to_char(local_time, 'HH24:MI') from support_plan_activity_schedule
				where support_plan_id=:id and ordinal=1
				""").param("id", planId).query(String.class).single()).isEqualTo("18:00");
		mvc.perform(post("/api/v1/support-plans").with(user(userId))
				.header("Idempotency-Key", "personalized-plan-create")
				.contentType(MediaType.APPLICATION_JSON).content(body(evaluationId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.supportPlanId").value(planId.toString()));
	}

	@Test
	void acceptsBoundedEvidenceAndEndsNativeActionWithoutRewritingOccurrenceHistory()
			throws Exception {
		var userId = insertProfile();
		var evaluationId = evaluation(userId, "MINIMAL", "MINIMAL", false);
		var now = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS);
		var today = now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
		var appointmentId = UUID.randomUUID().toString();

		var plan = SupportPlanEntity.nativeDraft(UUID.randomUUID(), userId, evaluationId,
				"mb-support-routing-capstone-v2", now, "support-plan-policy-v3",
				"PLUS", "DEMO", "service-entitlement-v1", 1, now,
				"DOMAIN_AWARE_WELLBEING_SUPPORT", "Kế hoạch hỗ trợ sức khỏe tinh thần tổng quát.",
				"NEGATIVE_SAFETY_SCREEN", "NO_ITEM9_SIGNAL", "MB-SAFETY-PHQ9-001-v1",
				"STANDARD_SAFETY_GUIDANCE", "Tìm hỗ trợ khẩn cấp khi cần.", 2, now);
		plan = plans.saveAndFlush(plan);
		var focus = focuses.saveAndFlush(new SupportPlanFocusEntity(UUID.randomUUID(), plan.id(), userId,
				1, "Kết nối với hỗ trợ phù hợp", "Thực hiện bước đã chọn.", now));
		var action = actions.saveAndFlush(new PlanActionEntity(UUID.randomUUID(), plan.id(), focus.id(), userId,
				1, "PROFESSIONAL_SUPPORT", "ATTEND_BOOKED_SUPPORT", "Giữ kết nối hỗ trợ.",
				new SourceReference("CONSULTATION", "APPOINTMENT", appointmentId, "1",
						"consultation-routing-v1", null, null, now),
				new Provenance("USER_SELECTED", null, null, now),
				new Presentation("Buổi hỗ trợ đã đặt", "Theo dõi lịch đã xác nhận.", null, null), now));
		var pendingAction = actions.saveAndFlush(new PlanActionEntity(UUID.randomUUID(), plan.id(), focus.id(),
				userId, 2, "SELF_REPORT_REVIEW", "REVIEW_PLAN", "Review current plan progress.", null,
				new Provenance("USER_SELECTED", null, null, now),
				new Presentation("Review plan", "Review the current support plan.", null, null), now));
		plan.activate(now);
		plan = plans.saveAndFlush(plan);
		action.activate(now);
		action = actions.saveAndFlush(action);
		pendingAction.activate(now);
		pendingAction = actions.saveAndFlush(pendingAction);
		var schedule = schedules.saveAndFlush(new SupportPlanActivityScheduleEntity(UUID.randomUUID(),
				plan.id(), userId, 1, plan.version(), action, "support-plan-schedule-v3",
				"DAILY", null, LocalTime.of(9, 0), "Asia/Ho_Chi_Minh", today, true, now));

		var body = mvc.perform(get("/api/v2/support-plan-occurrences").with(user(userId))
				.param("from", today.toString()).param("through", today.plusDays(1).toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.representationMode").value("NATIVE_V3"))
				.andExpect(jsonPath("$.occurrences.length()").value(2))
				.andExpect(jsonPath("$.occurrences[0].actionType").value("PROFESSIONAL_SUPPORT"))
				.andReturn().getResponse().getContentAsString();
		var occurrences = objectMapper.readTree(body).path("occurrences");
		var firstOccurrenceId = UUID.fromString(occurrences.get(0).path("occurrenceId").asText());
		var secondOccurrenceId = UUID.fromString(occurrences.get(1).path("occurrenceId").asText());
		var boundedFact = new OwnerFact("CONSULTATION", "APPOINTMENT_FULFILMENT",
				UUID.randomUUID().toString(), "1", now.plusSeconds(1), now.plusSeconds(2));
		var linkedAppointment = new LinkedActionSource("CONSULTATION", "APPOINTMENT", appointmentId, "1");
		var fact = new AuthoritativeOwnerFact(userId, linkedAppointment, boundedFact);

		assertThatThrownBy(() -> activityOccurrences.completeFromOwnerFact(userId, firstOccurrenceId,
				new AuthoritativeOwnerFact(UUID.randomUUID(), linkedAppointment, boundedFact), UUID.randomUUID()))
				.hasMessageContaining("subject does not own the PlanAction");
		assertThatThrownBy(() -> activityOccurrences.completeFromOwnerFact(userId, firstOccurrenceId,
				new AuthoritativeOwnerFact(userId,
						new LinkedActionSource("CONSULTATION", "APPOINTMENT",
								UUID.randomUUID().toString(), "1"),
						boundedFact), UUID.randomUUID()))
				.hasMessageContaining("not linked to the PlanAction source");
		assertThat(jdbc.sql("select state from support_plan_activity_occurrence where id=:id")
				.param("id", firstOccurrenceId).query(String.class).single()).isEqualTo("SCHEDULED");

		var completed = activityOccurrences.completeFromOwnerFact(userId, firstOccurrenceId, fact,
				UUID.randomUUID());
		var replayed = activityOccurrences.completeFromOwnerFact(userId, firstOccurrenceId, fact,
				UUID.randomUUID());
		assertThat(completed.state()).isEqualTo("COMPLETED");
		assertThat(replayed.version()).isEqualTo(completed.version());
		assertThatThrownBy(() -> activityOccurrences.completeFromOwnerFact(userId, secondOccurrenceId,
				fact, UUID.randomUUID())).hasMessageContaining("already completes another action occurrence");
		assertThat(jdbc.sql("""
				select count(*) from outbox_event
				where aggregate_id=:id
				  and message_type='care.support-plan.action-occurrence-changed'
				  and schema_version='2.0'
				""").param("id", firstOccurrenceId).query(Long.class).single()).isEqualTo(1);
		var eventPayload = objectMapper.readTree(jdbc.sql("""
				select payload::text from outbox_event
				where aggregate_id=:id and message_type='care.support-plan.action-occurrence-changed'
				""").param("id", firstOccurrenceId).query(String.class).single());
		assertThat(eventPayload.path("ownerFact").path("ownerDomain").asText()).isEqualTo("CONSULTATION");
		assertThat(eventPayload.path("ownerFact").path("kind").asText())
				.isEqualTo("APPOINTMENT_FULFILMENT");
		assertThat(eventPayload.toString()).doesNotContain("appointmentNote", "journalBody", "rawScore");

		mvc.perform(get("/api/v1/support-plan-occurrences/{id}", firstOccurrenceId).with(user(userId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED"));
		var beforeActionEnd = mvc.perform(get("/api/v2/support-plans/{id}", plan.id()).with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.focuses[0].actions[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.focuses[0].actions[0].schedules[0].status").value("ACTIVE"))
				.andReturn().getResponse().getContentAsString();

		var ended = activityOccurrences.endAction(userId, action.id());
		assertThat(ended.status()).isEqualTo("ENDED");
		assertThat(ended.actionVersion()).isEqualTo(2);
		assertThat(ended.endedScheduleCount()).isEqualTo(1);
		assertThat(ended.cancelledOccurrenceCount()).isEqualTo(1);
		var afterActionEnd = mvc.perform(get("/api/v2/support-plans/{id}", plan.id()).with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.focuses[0].actions[0].status").value("ENDED"))
				.andExpect(jsonPath("$.focuses[0].actions[0].schedules[0].status").value("ENDED"))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(afterActionEnd).path("updatedAt"))
				.isNotEqualTo(objectMapper.readTree(beforeActionEnd).path("updatedAt"));
		var endedReplay = activityOccurrences.endAction(userId, action.id());
		assertThat(endedReplay.actionVersion()).isEqualTo(ended.actionVersion());
		assertThat(endedReplay.endedScheduleCount()).isZero();
		assertThat(endedReplay.cancelledOccurrenceCount()).isZero();
		var afterReplay = mvc.perform(get("/api/v2/support-plans/{id}", plan.id()).with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"2\""))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(afterReplay)).isEqualTo(objectMapper.readTree(afterActionEnd));
		mvc.perform(get("/api/v2/support-plans/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.focuses[0].actions[0].status").value("ENDED"));
		assertThat(jdbc.sql("select status || '|' || source_action_version "
				+ "from support_plan_activity_schedule where id=:id")
				.param("id", schedule.id()).query(String.class).single()).isEqualTo("ENDED|1");
		assertThat(jdbc.sql("select state || '|' || coalesce(state_reason, '') || '|' || source_action_version "
				+ "from support_plan_activity_occurrence where id=:id")
				.param("id", firstOccurrenceId).query(String.class).single()).isEqualTo("COMPLETED||1");
		assertThat(jdbc.sql("select state || '|' || state_reason || '|' || source_action_version "
				+ "from support_plan_activity_occurrence where id=:id")
				.param("id", secondOccurrenceId).query(String.class).single())
				.isEqualTo("CANCELLED|ACTION_ENDED|1");

		mvc.perform(get("/api/v2/support-plan-occurrences").with(user(userId))
				.param("from", today.plusDays(1).toString())
				.param("through", today.plusDays(2).toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.occurrences.length()").value(1))
				.andExpect(jsonPath("$.occurrences[0].occurrenceId").value(secondOccurrenceId.toString()))
				.andExpect(jsonPath("$.occurrences[0].state").value("CANCELLED"))
				.andExpect(jsonPath("$.occurrences[0].stateReason").value("ACTION_ENDED"));
		assertThat(jdbc.sql("select count(*) from support_plan_activity_occurrence where source_action_id=:id")
				.param("id", action.id()).query(Long.class).single()).isEqualTo(2);

		plan = plans.findByIdAndUserId(plan.id(), userId).orElseThrow();
		plan.complete("USER_DECISION", Instant.now().truncatedTo(ChronoUnit.MICROS));
		plans.saveAndFlush(plan);
		var terminalActionId = pendingAction.id();
		assertThatThrownBy(() -> activityOccurrences.endAction(userId, terminalActionId))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_NOT_MUTABLE"));
		mvc.perform(get("/api/v2/support-plans/{id}", plan.id()).with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"3\""))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.focuses[0].actions[1].status").value("ACTIVE"));
	}

	@Test
	void projectsAnExistingV2PlanWithoutCreatingNativeRowsOrLosingExactResourceProvenance()
			throws Exception {
		var userId = insertProfile();
		var draft = createPlan(userId, evaluation(userId, "MINIMAL", "MILD", false),
				"support-plan-v3-adapter-create");
		var planId = UUID.fromString(draft.path("supportPlanId").asText());
		var selectedResource = draft.path("slots").get(0).path("selectedResource");
		mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-v3-adapter-activate"))
				.andExpect(status().isOk());

		var first = mvc.perform(get("/api/v2/support-plans/current").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.supportPlanId").value(planId.toString()))
				.andExpect(jsonPath("$.modelVersion").value(3))
				.andExpect(jsonPath("$.representationMode").value("V2_RESOURCE_ADAPTER"))
				.andExpect(jsonPath("$.focuses[0].focusId").isString())
				.andExpect(jsonPath("$.focuses[0].title").isNotEmpty())
				.andExpect(jsonPath("$.focuses[0].actions[0].actionType").value("RESOURCE"))
				.andExpect(jsonPath("$.focuses[0].actions[0].source.ownerDomain").value("CONTENT"))
				.andReturn().getResponse().getContentAsString();
		var projection = objectMapper.readTree(first);
		var matchingAction = projection.path("focuses").findValues("actions").stream()
				.flatMap(node -> java.util.stream.StreamSupport.stream(node.spliterator(), false))
				.filter(node -> selectedResource.path("resourceId").asText()
						.equals(node.path("source").path("sourceId").asText()))
				.findFirst().orElseThrow();
		assertThat(matchingAction.path("source").path("sourceRevision").asText())
				.isEqualTo(selectedResource.path("contentVersion").asText());
		assertThat(matchingAction.path("source").path("publicationId").asText())
				.isEqualTo(selectedResource.path("publicationId").asText());
		assertThat(matchingAction.path("source").path("eligibilityPolicyVersion").asText())
				.isEqualTo("content-eligibility-v1");
		var replay = mvc.perform(get("/api/v2/support-plans/current").with(user(userId)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(replay)).isEqualTo(projection);

		var today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
		mvc.perform(get("/api/v2/support-plan-occurrences").with(user(userId))
				.param("from", today.toString()).param("through", today.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.representationMode").value("V2_RESOURCE_ADAPTER"))
				.andExpect(jsonPath("$.occurrences[0].actionType").value("RESOURCE"))
				.andExpect(jsonPath("$.occurrences[0].completionEvidence").doesNotExist());
		assertThat(jdbc.sql("select count(*) from support_plan_focus where support_plan_id=:id")
				.param("id", planId).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select count(*) from support_plan_action where support_plan_id=:id")
				.param("id", planId).query(Long.class).single()).isZero();
	}

	@Test
	void userReviewsRejectsOrAtomicallyAcceptsAnExactSpecialistProposal() throws Exception {
		var userId = insertProfile();
		var specialistId = UUID.randomUUID();
		var current = createPlan(userId, evaluation(userId, "MINIMAL", "MINIMAL", false),
				"plan-change-current-create");
		var currentId = UUID.fromString(current.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", currentId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "plan-change-current-activate"))
				.andExpect(status().isOk());

		var firstProposalId = UUID.randomUUID();
		when(resourceProposals.get(org.mockito.ArgumentMatchers.eq(firstProposalId), anyString(), any()))
				.thenReturn(resourceProposal(firstProposalId, userId, specialistId,
						"00000000-0000-4000-8000-000000000205"));
		var rejected = mvc.perform(post("/api/v1/plan-change-requests").with(user(userId))
				.header("Idempotency-Key", "plan-change-create-reject")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"proposalId\":\"" + firstProposalId + "\"}"))
				.andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
				.andExpect(jsonPath("$.status").value("READY_FOR_REVIEW"))
				.andExpect(jsonPath("$.proposedResource.resourceId")
						.value("00000000-0000-4000-8000-000000000205"))
				.andReturn().getResponse().getContentAsString();
		var rejectedId = UUID.fromString(objectMapper.readTree(rejected).path("requestId").asText());
		mvc.perform(put("/api/v1/plan-change-requests/{id}/decision", rejectedId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "plan-change-reject-command")
				.contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
		assertThat(jdbc.sql("select status from support_plan where id=:id").param("id", currentId)
				.query(String.class).single()).isEqualTo("ACTIVE");

		var secondProposalId = UUID.randomUUID();
		when(resourceProposals.get(org.mockito.ArgumentMatchers.eq(secondProposalId), anyString(), any()))
				.thenReturn(resourceProposal(secondProposalId, userId, specialistId,
						"00000000-0000-4000-8000-000000000205"));
		var created = mvc.perform(post("/api/v1/plan-change-requests").with(user(userId))
				.header("Idempotency-Key", "plan-change-create-accept")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"proposalId\":\"" + secondProposalId + "\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY_FOR_REVIEW"))
				.andReturn().getResponse().getContentAsString();
		var requestId = UUID.fromString(objectMapper.readTree(created).path("requestId").asText());
		mvc.perform(put("/api/v1/plan-change-requests/{id}/decision", requestId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "plan-change-reject-command")
				.contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPT\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		for (int retry = 0; retry < 2; retry++) {
			mvc.perform(put("/api/v1/plan-change-requests/{id}/decision", requestId).with(user(userId))
					.header("If-Match", "\"0\"").header("Idempotency-Key", "plan-change-accept-command")
					.contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPT\"}"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"))
					.andExpect(jsonPath("$.replacementSupportPlanId").isString());
		}
		mvc.perform(get("/api/v1/specialist/plan-change-requests/by-proposal/{id}", secondProposalId)
				.with(jwt().jwt(token -> token.subject(specialistId.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
		assertThat(jdbc.sql("select status from support_plan where id=:id").param("id", currentId)
				.query(String.class).single()).isEqualTo("SUPERSEDED");
		assertThat(jdbc.sql("select count(*) from support_plan where user_id=:id and status='ACTIVE'")
				.param("id", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_command where plan_change_request_id=:id")
				.param("id", requestId).query(Long.class).single()).isEqualTo(1);
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

		assertThat(jdbc.sql("select count(*) from support_plan where user_id in (:stale, :unavailable)")
				.param("stale", staleUser).param("unavailable", unavailableUser)
				.query(Long.class).single()).isZero();
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

	@Test
	void swapsOnlyAnAdmittedChoiceAndActivatesThenReloadsTheAuthoritativeCurrentPlan() throws Exception {
		var userId = insertProfile();
		var evaluationId = evaluation(userId, "MINIMAL", "MINIMAL", false);
		var draft = createPlan(userId, evaluationId, "support-plan-choice-create");
		var planId = UUID.fromString(draft.path("supportPlanId").asText());
		var firstSlot = draft.path("slots").get(0);
		var secondSlot = draft.path("slots").get(1);
		var alternative = firstSlot.path("allowedAlternatives").get(0);
		var choices = objectMapper.createObjectNode();
		var selections = choices.putArray("slotSelections");
		selections.addObject().put("slotId", firstSlot.path("slotId").asText())
				.put("resourceId", alternative.path("resourceId").asText())
				.put("contentVersion", alternative.path("contentVersion").asText());
		selections.addObject().put("slotId", secondSlot.path("slotId").asText())
				.put("resourceId", secondSlot.path("selectedResource").path("resourceId").asText())
				.put("contentVersion", secondSlot.path("selectedResource").path("contentVersion").asText());

		var changed = mvc.perform(put("/api/v1/support-plans/{id}/choices", planId).with(user(userId))
				.header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(choices)))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.slots[0].selectedResource.resourceId")
						.value(alternative.path("resourceId").asText()))
				.andReturn().getResponse().getContentAsString();
		var unchanged = mvc.perform(put("/api/v1/support-plans/{id}/choices", planId).with(user(userId))
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(choices)))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(unchanged)).isEqualTo(objectMapper.readTree(changed));

		var activated = mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"1\"").header("Idempotency-Key", "support-plan-activate-01"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.activatedAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		var activationReplay = mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"1\"").header("Idempotency-Key", "support-plan-activate-01"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
				.andReturn().getResponse().getContentAsString();
		var current = mvc.perform(get("/api/v1/support-plans/current").with(user(userId)))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
				.andReturn().getResponse().getContentAsString();

		assertThat(objectMapper.readTree(activationReplay)).isEqualTo(objectMapper.readTree(activated));
		assertThat(objectMapper.readTree(current)).isEqualTo(objectMapper.readTree(activated));
		mvc.perform(get("/api/v1/support-plans/current-draft").with(user(userId)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_DRAFT_NOT_FOUND"));
		assertThat(jdbc.sql("select count(*) from support_plan where user_id = :id and status = 'ACTIVE'")
				.param("id", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_command where user_id = :id")
				.param("id", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id = :id and message_type = 'care.support-plan.activated'")
				.param("id", planId).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void removesAnOptionalChoiceButRejectsInjectedAndMissingCoreChoices() throws Exception {
		var optionalUser = insertProfile();
		var optionalEvaluation = evaluation(optionalUser, "MODERATE", "MODERATE", false);
		var optionalDraft = createPlan(optionalUser, optionalEvaluation, "support-plan-optional-create");
		var optionalPlanId = UUID.fromString(optionalDraft.path("supportPlanId").asText());
		var kept = optionalDraft.path("slots").get(0);
		var optionalChoices = objectMapper.createObjectNode();
		optionalChoices.putArray("slotSelections").addObject()
				.put("slotId", kept.path("slotId").asText())
				.put("resourceId", kept.path("selectedResource").path("resourceId").asText())
				.put("contentVersion", kept.path("selectedResource").path("contentVersion").asText());

		mvc.perform(put("/api/v1/support-plans/{id}/choices", optionalPlanId).with(user(optionalUser))
				.header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(optionalChoices)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.selectedResourceCount").value(1))
				.andExpect(jsonPath("$.slots[1].selectedResource").doesNotExist());

		var coreUser = insertProfile();
		var coreEvaluation = evaluation(coreUser, "MILD", "MILD", false);
		var coreDraft = createPlan(coreUser, coreEvaluation, "support-plan-core-create");
		var corePlanId = UUID.fromString(coreDraft.path("supportPlanId").asText());
		var injected = objectMapper.createObjectNode();
		injected.putArray("slotSelections").addObject()
				.put("slotId", coreDraft.path("slots").get(0).path("slotId").asText())
				.put("resourceId", UUID.randomUUID().toString()).put("contentVersion", "0");

		mvc.perform(put("/api/v1/support-plans/{id}/choices", corePlanId).with(user(coreUser))
				.header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(injected)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPORT_PLAN_INVALID_CHOICE"));
		assertThat(jdbc.sql("select version from support_plan where id = :id").param("id", corePlanId)
				.query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select count(*) from support_plan_command where support_plan_id = :id")
				.param("id", corePlanId).query(Long.class).single()).isZero();
	}

	@Test
	void activationFailsClosedForFreeStaleAndVersionConflict() throws Exception {
		var freeUser = insertProfile();
		var freeDraft = createPlan(freeUser, evaluation(freeUser, "MINIMAL", "MINIMAL", false),
				"support-plan-free-activation-create");
		var freePlanId = UUID.fromString(freeDraft.path("supportPlanId").asText());
		when(entitlements.current(any(), anyString(), any())).thenReturn(new CurrentEntitlementResponse(freeUser,
				ServicePackage.FREE, EntitlementSource.DEFAULT_FREE, null, null, null,
				"service-entitlement-v1", 0, Instant.parse("2026-09-19T00:00:00Z")));
		mvc.perform(post("/api/v1/support-plans/{id}/activate", freePlanId).with(user(freeUser))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-free-activate"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_ENTITLEMENT_REQUIRED"));

		var staleUser = insertProfile();
		when(entitlements.current(any(), anyString(), any())).thenAnswer(invocation -> paid(invocation.getArgument(0)));
		var staleDraft = createPlan(staleUser, evaluation(staleUser, "MINIMAL", "MINIMAL", false),
				"support-plan-stale-activation-create");
		var stalePlanId = UUID.fromString(staleDraft.path("supportPlanId").asText());
		eligible(ResourceEligibilityOutcome.STALE, ResourceEligibilityReasonCode.CONTENT_VERSION_STALE);
		mvc.perform(post("/api/v1/support-plans/{id}/activate", stalePlanId).with(user(staleUser))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-stale-activate"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_VERSION_STALE"));
		mvc.perform(post("/api/v1/support-plans/{id}/activate", stalePlanId).with(user(staleUser))
				.header("If-Match", "\"9\"").header("Idempotency-Key", "support-plan-version-fail"))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_VERSION_MISMATCH"));

		assertThat(jdbc.sql("select count(*) from support_plan where status = 'ACTIVE' and user_id in (:free,:stale)")
				.param("free", freeUser).param("stale", staleUser).query(Long.class).single()).isZero();
	}

	@Test
	void concurrentActivationProducesExactlyOneCurrentPlan() throws Exception {
		var userId = insertProfile();
		var draft = createPlan(userId, evaluation(userId, "MINIMAL", "MINIMAL", false),
				"support-plan-concurrent-activation-create");
		var planId = UUID.fromString(draft.path("supportPlanId").asText());
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> activateAfter(start, userId, planId, "support-plan-activate-concurrent-1"));
			var second = executor.submit(() -> activateAfter(start, userId, planId, "support-plan-activate-concurrent-2"));
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(200, 409);
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(jdbc.sql("select count(*) from support_plan where user_id = :id and status = 'ACTIVE'")
				.param("id", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_command where user_id = :id and command_type = 'ACTIVATE'")
				.param("id", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_plan_activity_schedule where user_id = :id")
				.param("id", userId).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from support_plan_activity_occurrence where user_id = :id")
				.param("id", userId).query(Long.class).single()).isEqualTo(4);
	}

	@Test
	void generatesExactlyOnceTracksUserInputAndAppliesPauseResumeComplete() throws Exception {
		var userId = insertProfile();
		var draft = createPlan(userId, evaluation(userId, "MINIMAL", "MINIMAL", false),
				"support-plan-occurrence-create");
		var planId = UUID.fromString(draft.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-occurrence-activate"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));

		var bounds = jdbc.sql("""
				select min(local_date) as from_date, max(local_date) as through_date
				from support_plan_activity_occurrence where user_id = :id
				""").param("id", userId).query((row, index) -> List.of(
				row.getObject("from_date", LocalDate.class), row.getObject("through_date", LocalDate.class))).single();
		String listPath = "/api/v1/support-plan-occurrences?from=" + bounds.get(0) + "&through=" + bounds.get(1);
		var first = mvc.perform(get(listPath).with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.schedulePolicyVersion").value("support-plan-activity-schedule-v1"))
				.andExpect(jsonPath("$.interpretationCode")
						.value("SELF_REPORTED_WELLBEING_ACTIVITY_NOT_TREATMENT_ADHERENCE"))
				.andReturn().getResponse().getContentAsString();
		var repeated = mvc.perform(get(listPath).with(user(userId))).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(repeated)).isEqualTo(objectMapper.readTree(first));
		assertThat(jdbc.sql("select count(*) from support_plan_activity_occurrence where user_id = :id")
				.param("id", userId).query(Long.class).single()).isEqualTo(4);

		var listedOccurrences = objectMapper.readTree(first).path("occurrences");
		var occurrence = listedOccurrences.get(0);
		var occurrenceId = UUID.fromString(occurrence.path("occurrenceId").asText());
		var skippedOccurrenceId = UUID.fromString(listedOccurrences.get(1).path("occurrenceId").asText());
		var completed = mvc.perform(put("/api/v1/support-plan-occurrences/{id}/state", occurrenceId)
				.with(user(userId)).header("If-Match", "\"0\"")
				.header("X-Correlation-ID", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"COMPLETED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.state").value("COMPLETED"))
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.hidden").value(false))
				.andExpect(jsonPath("$.summaryReuseApproved").value(false))
				.andExpect(jsonPath("$.engagementUpdatedAt").isString())
				.andReturn().getResponse().getContentAsString();
		var completedReplay = mvc.perform(put("/api/v1/support-plan-occurrences/{id}/state", occurrenceId)
				.with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"COMPLETED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(completedReplay)).isEqualTo(objectMapper.readTree(completed));
		mvc.perform(put("/api/v1/support-plan-occurrences/{id}/state", occurrenceId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"SKIPPED\"}"))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("OCCURRENCE_VERSION_MISMATCH"));
		mvc.perform(put("/api/v1/support-plan-occurrences/{id}/state", skippedOccurrenceId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"SKIPPED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.state").value("SKIPPED"))
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.hidden").value(false))
				.andExpect(jsonPath("$.summaryReuseApproved").value(false))
				.andExpect(jsonPath("$.engagementUpdatedAt").isString());
		assertThat(jdbc.sql("""
				select count(*) from outbox_event
				where aggregate_id = :id and message_type = 'care.support-plan.engagement-changed'
				""").param("id", occurrenceId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("""
				select payload ->> 'state' from outbox_event
				where aggregate_id = :id and message_type = 'care.support-plan.engagement-changed'
				""").param("id", occurrenceId).query(String.class).single()).isEqualTo("COMPLETED");
		assertThat(jdbc.sql("""
				select count(*) from outbox_event
				where aggregate_id = :id and message_type = 'care.support-plan.engagement-changed'
				""").param("id", skippedOccurrenceId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("""
				select payload ->> 'state' from outbox_event
				where aggregate_id = :id and message_type = 'care.support-plan.engagement-changed'
				""").param("id", skippedOccurrenceId).query(String.class).single()).isEqualTo("SKIPPED");

		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"PAUSED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.status").value("PAUSED"));
		assertThat(jdbc.sql("""
				select count(*) from support_plan_activity_occurrence
				where support_plan_id = :id and state = 'CANCELLED' and state_reason = 'PLAN_PAUSED'
				""").param("id", planId).query(Long.class).single()).isPositive();

		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		assertThat(jdbc.sql("""
				select count(*) from support_plan_activity_occurrence
				where support_plan_id = :id and state = 'CANCELLED' and state_reason = 'PLAN_PAUSED'
				""").param("id", planId).query(Long.class).single()).isZero();

		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"3\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"COMPLETED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"4\""))
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		mvc.perform(get("/api/v1/support-plans/current").with(user(userId)))
				.andExpect(status().isNotFound());
	}

	@Test
	void stateChangePreservesHiddenVisibility() throws Exception {
		var userId = insertProfile();
		var draft = createPlan(userId, evaluation(userId, "MINIMAL", "MINIMAL", false),
				"support-plan-hidden-state-create");
		var planId = UUID.fromString(draft.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-hidden-state-activate"))
				.andExpect(status().isOk());
		var occurrenceId = jdbc.sql("""
				select id from support_plan_activity_occurrence
				where user_id = :id order by scheduled_at, id limit 1
				""").param("id", userId).query(UUID.class).single();

		mvc.perform(put("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"state":"SCHEDULED","hidden":true,"helpfulness":null,"barrierCode":null,
						 "reflection":null,"summaryReuseApproved":false}
						"""))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.hidden").value(true));

		mvc.perform(put("/api/v1/support-plan-occurrences/{id}/state", occurrenceId)
				.with(user(userId)).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"COMPLETED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.state").value("COMPLETED"))
				.andExpect(jsonPath("$.hidden").value(true))
				.andExpect(jsonPath("$.engagementUpdatedAt").isString());
		assertThat(jdbc.sql("""
				select count(*) from outbox_event
				where aggregate_id = :id and aggregate_version = 2
				  and message_type = 'care.support-plan.engagement-changed'
				""").param("id", occurrenceId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("""
				select (payload ->> 'hidden')::boolean from outbox_event
				where aggregate_id = :id and aggregate_version = 2
				  and message_type = 'care.support-plan.engagement-changed'
				""").param("id", occurrenceId).query(Boolean.class).single()).isTrue();
	}

	@Test
	void stateReplayPreservesExistingEngagementMetadata() throws Exception {
		var userId = insertProfile();
		var draft = createPlan(userId, evaluation(userId, "MINIMAL", "MINIMAL", false),
				"support-plan-state-replay-create");
		var planId = UUID.fromString(draft.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-state-replay-activate"))
				.andExpect(status().isOk());
		var occurrenceId = jdbc.sql("""
				select id from support_plan_activity_occurrence
				where user_id = :id order by scheduled_at, id limit 1
				""").param("id", userId).query(UUID.class).single();
		var completed = """
				{"state":"COMPLETED","hidden":true,"helpfulness":"HELPFUL","barrierCode":null,
				 "reflection":"Hoạt động này hữu ích.","summaryReuseApproved":true}
				""";

		var engagement = mvc.perform(put("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content(completed))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andReturn().getResponse().getContentAsString();
		var stateReplay = mvc.perform(put("/api/v1/support-plan-occurrences/{id}/state", occurrenceId)
				.with(user(userId)).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"COMPLETED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.hidden").value(true))
				.andExpect(jsonPath("$.helpfulness").value("HELPFUL"))
				.andExpect(jsonPath("$.reflection").value("Hoạt động này hữu ích."))
				.andExpect(jsonPath("$.summaryReuseApproved").value(true))
				.andReturn().getResponse().getContentAsString();

		assertThat(objectMapper.readTree(stateReplay)).isEqualTo(objectMapper.readTree(engagement));
		assertThat(jdbc.sql("""
				select count(*) from outbox_event
				where aggregate_id = :id and message_type = 'care.support-plan.engagement-changed'
				""").param("id", occurrenceId).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void replacesReopensDeletesAndReloadsOwnedEngagementWithoutPublishingReflection() throws Exception {
		var userId = insertProfile();
		var otherUser = insertProfile();
		var draft = createPlan(userId, evaluation(userId, "MINIMAL", "MINIMAL", false),
				"support-plan-engagement-create");
		var planId = UUID.fromString(draft.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-engagement-activate"))
				.andExpect(status().isOk());

		var localDate = jdbc.sql("""
				select min(local_date) from support_plan_activity_occurrence where user_id = :id
				""").param("id", userId).query(LocalDate.class).single();
		var listPath = "/api/v1/support-plan-occurrences?from=" + localDate + "&through=" + localDate;
		var listed = mvc.perform(get(listPath).with(user(userId))).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		var occurrence = objectMapper.readTree(listed).path("occurrences").get(0);
		var occurrenceId = UUID.fromString(occurrence.path("occurrenceId").asText());
		var sourceContentVersion = occurrence.path("source").path("contentVersion").asText();
		assertThat(sourceContentVersion).isNotBlank();
		var correlationId = UUID.randomUUID();
		var completed = """
				{"state":"COMPLETED","hidden":false,"helpfulness":"HELPFUL","barrierCode":null,
				 "reflection":"  Hoạt động này giúp tôi chậm lại.  ","summaryReuseApproved":true}
				""";

		var first = mvc.perform(put("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"0\"").header("X-Correlation-ID", correlationId)
				.contentType(MediaType.APPLICATION_JSON).content(completed))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.state").value("COMPLETED"))
				.andExpect(jsonPath("$.helpfulness").value("HELPFUL"))
				.andExpect(jsonPath("$.reflection").value("Hoạt động này giúp tôi chậm lại."))
				.andExpect(jsonPath("$.summaryReuseApproved").value(true))
				.andExpect(jsonPath("$.source.supportPlanVersion").value(1))
				.andExpect(jsonPath("$.source.contentVersion").value(sourceContentVersion))
				.andReturn().getResponse().getContentAsString();
		var replay = mvc.perform(put("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content(completed)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(replay)).isEqualTo(objectMapper.readTree(first));
		assertThat(jdbc.sql("""
				select count(*) from outbox_event
				where aggregate_id = :id and message_type = 'care.support-plan.engagement-changed'
				""").param("id", occurrenceId).query(Long.class).single()).isEqualTo(1);
		var payload = jdbc.sql("""
				select payload::text from outbox_event
				where aggregate_id = :id and message_type = 'care.support-plan.engagement-changed'
				""").param("id", occurrenceId).query(String.class).single();
		var eventPayload = objectMapper.readTree(payload);
		assertThat(eventPayload.path("helpfulness").asText()).isEqualTo("HELPFUL");
		assertThat(eventPayload.has("reflection")).isFalse();
		mvc.perform(get(listPath).with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isForbidden());

		mvc.perform(put("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(otherUser)).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content(completed)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("OCCURRENCE_NOT_FOUND"));
		mvc.perform(put("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"state":"COMPLETED","hidden":false,"helpfulness":null,
						 "barrierCode":"LOW_ENERGY","reflection":null,"summaryReuseApproved":false}
						""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OCCURRENCE_ENGAGEMENT_INVALID"));

		var skipped = """
				{"state":"SKIPPED","hidden":true,"helpfulness":null,"barrierCode":"LOW_ENERGY",
				 "reflection":"Hôm nay tôi cần nghỉ.","summaryReuseApproved":false}
				""";
		mvc.perform(put("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content(skipped)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.state").value("SKIPPED"))
				.andExpect(jsonPath("$.hidden").value(true))
				.andExpect(jsonPath("$.barrierCode").value("LOW_ENERGY"));
		mvc.perform(put("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content(completed)).andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("OCCURRENCE_VERSION_MISMATCH"));

		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"PAUSED\"}")).andExpect(status().isOk());
		mvc.perform(delete("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"2\""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_NOT_ACTIVE"));
		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());

		var deleted = mvc.perform(delete("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"2\""))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""))
				.andExpect(jsonPath("$.state").value("SCHEDULED"))
				.andExpect(jsonPath("$.hidden").value(false))
				.andExpect(jsonPath("$.helpfulness").doesNotExist())
				.andExpect(jsonPath("$.barrierCode").doesNotExist())
				.andExpect(jsonPath("$.reflection").doesNotExist())
				.andExpect(jsonPath("$.summaryReuseApproved").value(false))
				.andReturn().getResponse().getContentAsString();
		var deleteReplay = mvc.perform(delete("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"2\""))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(deleteReplay)).isEqualTo(objectMapper.readTree(deleted));

		mvc.perform(get(listPath).with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.occurrences[0].version").value(3))
				.andExpect(jsonPath("$.occurrences[0].state").value("SCHEDULED"))
				.andExpect(jsonPath("$.occurrences[0].engagementUpdatedAt").isString());
	}

	@Test
	void concurrentEngagementReplacementsCommitOneVersionedResult() throws Exception {
		var userId = insertProfile();
		var draft = createPlan(userId, evaluation(userId, "MINIMAL", "MINIMAL", false),
				"support-plan-engagement-concurrency-create");
		var planId = UUID.fromString(draft.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"0\"")
				.header("Idempotency-Key", "support-plan-engagement-concurrency-activate"))
				.andExpect(status().isOk());
		var occurrenceId = jdbc.sql("""
				select id from support_plan_activity_occurrence
				where user_id = :id order by scheduled_at, id limit 1
				""").param("id", userId).query(UUID.class).single();
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var complete = executor.submit(() -> engagementAfter(start, userId, occurrenceId,
					"{\"state\":\"COMPLETED\",\"hidden\":false,\"helpfulness\":null,\"barrierCode\":null,\"reflection\":null,\"summaryReuseApproved\":false}"));
			var skip = executor.submit(() -> engagementAfter(start, userId, occurrenceId,
					"{\"state\":\"SKIPPED\",\"hidden\":false,\"helpfulness\":null,\"barrierCode\":\"OTHER\",\"reflection\":null,\"summaryReuseApproved\":false}"));
			start.countDown();
			assertThat(List.of(complete.get(20, TimeUnit.SECONDS), skip.get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(200, 412);
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(jdbc.sql("select version from support_plan_activity_occurrence where id = :id")
				.param("id", occurrenceId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("""
				select count(*) from outbox_event
				where aggregate_id = :id and message_type = 'care.support-plan.engagement-changed'
				""").param("id", occurrenceId).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void lifecycleGuardsRepeatsOwnershipAndTerminalHistoryRemainAuthoritative() throws Exception {
		var userId = insertProfile();
		var otherUser = insertProfile();
		var evaluationId = evaluation(userId, "MINIMAL", "MINIMAL", false);
		var draft = createPlan(userId, evaluationId, "support-plan-lifecycle-history-create");
		var planId = UUID.fromString(draft.path("supportPlanId").asText());

		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"PAUSED\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_TRANSITION_INVALID"));
		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISCARDED\",\"completionReason\":\"OTHER\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_COMPLETION_REASON_INVALID"));

		mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-lifecycle-history-activate"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"PAUSED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"PAUSED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""));
		evaluation(userId, "MODERATE", "MILD", false);
		mvc.perform(get("/api/v1/support-plans/current").with(user(userId)))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""))
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		var completed = mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"3\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"COMPLETED\",\"completionReason\":\"PLAN_NO_LONGER_FITS\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"4\""))
				.andExpect(jsonPath("$.completedAt").isString())
				.andExpect(jsonPath("$.completionReason").value("PLAN_NO_LONGER_FITS"))
				.andReturn().getResponse().getContentAsString();
		var repeated = mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"3\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"COMPLETED\",\"completionReason\":\"OTHER\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"4\""))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(repeated)).isEqualTo(objectMapper.readTree(completed));

		mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"4\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_TRANSITION_INVALID"));
		mvc.perform(get("/api/v1/support-plans/{id}", planId).with(user(otherUser)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/support-plans/history").with(user(otherUser)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());

		var detail = mvc.perform(get("/api/v1/support-plans/{id}", planId).with(user(userId)))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"4\""))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(detail)).isEqualTo(objectMapper.readTree(completed));
		mvc.perform(get("/api/v1/support-plans/history?limit=1").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].supportPlanId").value(planId.toString()))
				.andExpect(jsonPath("$.items[0].completionReason").value("PLAN_NO_LONGER_FITS"))
				.andExpect(jsonPath("$.nextCursor").doesNotExist())
				.andExpect(jsonPath("$.hasMore").value(false));
	}

	@Test
	void concurrentLifecycleCommandsCommitOnlyOneValidTransition() throws Exception {
		var userId = insertProfile();
		var draft = createPlan(userId, evaluation(userId, "MINIMAL", "MINIMAL", false),
				"support-plan-lifecycle-concurrency-create");
		var planId = UUID.fromString(draft.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-lifecycle-concurrency-activate"))
				.andExpect(status().isOk());

		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var pause = executor.submit(() -> transitionAfter(start, userId, planId, "PAUSED"));
			var complete = executor.submit(() -> transitionAfter(start, userId, planId, "COMPLETED"));
			start.countDown();
			assertThat(List.of(pause.get(20, TimeUnit.SECONDS), complete.get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(200, 412);
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(jdbc.sql("select version from support_plan where id = :id").param("id", planId)
				.query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select status from support_plan where id = :id").param("id", planId)
				.query(String.class).single()).isIn("PAUSED", "COMPLETED");
	}

	@Test
	void replacementAndDiscardHaveExplicitIdempotentLifecycleEffects() throws Exception {
		var userId = insertProfile();
		var evaluationId = evaluation(userId, "MINIMAL", "MINIMAL", false);
		var currentDraft = createPlan(userId, evaluationId, "support-plan-replace-current-create");
		var currentId = UUID.fromString(currentDraft.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", currentId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-replace-current-activate"))
				.andExpect(status().isOk());
		mvc.perform(put("/api/v1/support-plans/{id}/status", currentId).with(user(userId))
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"PAUSED\"}"))
				.andExpect(status().isOk());

		var replacementEvaluation = evaluation(userId, "MILD", "MINIMAL", false);
		var replacementDraft = createPlan(userId, replacementEvaluation, "support-plan-replacement-create");
		var replacementId = UUID.fromString(replacementDraft.path("supportPlanId").asText());
		var summaryId = insertReassessmentSummary(userId);
		String replacementBody = "{\"currentSupportPlanId\":\"" + currentId
				+ "\",\"currentVersion\":2,\"reassessmentSummaryId\":\"" + summaryId + "\"}";
		mvc.perform(post("/api/v1/support-plans/{id}/replacement-review", replacementId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content(replacementBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE"))
				.andExpect(jsonPath("$.reassessmentSummary.summaryId").value(summaryId.toString()))
				.andExpect(jsonPath("$.comparison").isArray());
		for (int retry = 0; retry < 2; retry++) {
			mvc.perform(post("/api/v1/support-plans/{id}/replace", replacementId).with(user(userId))
					.header("If-Match", "\"0\"")
					.header("Idempotency-Key", "support-plan-replacement-confirm")
					.contentType(MediaType.APPLICATION_JSON)
					.content(replacementBody))
					.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		}

		assertThat(jdbc.sql("select status from support_plan where id = :id").param("id", currentId)
				.query(String.class).single()).isEqualTo("SUPERSEDED");
		assertThat(jdbc.sql("""
				select count(*) from support_plan_activity_occurrence
				where support_plan_id = :id and state = 'CANCELLED' and state_reason = 'PLAN_REPLACED'
				""").param("id", currentId).query(Long.class).single()).isPositive();
		assertThat(jdbc.sql("""
				select count(*) from support_plan_activity_occurrence
				where support_plan_id = :id and state_reason = 'PLAN_PAUSED'
				""").param("id", currentId).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select count(*) from support_plan_activity_schedule where support_plan_id = :id")
				.param("id", replacementId).query(Long.class).single()).isEqualTo(3);
		assertThat(jdbc.sql("""
				select count(*) from support_plan_command
				where command_type = 'REPLACE' and support_plan_id = :replacementId
				  and source_support_plan_id = :currentId and reassessment_summary_id = :summaryId
				""").param("replacementId", replacementId).param("currentId", currentId)
				.param("summaryId", summaryId).query(Long.class).single()).isEqualTo(1);

		mvc.perform(put("/api/v1/support-plans/{id}/status", replacementId).with(user(userId))
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"COMPLETED\"}"))
				.andExpect(status().isOk());
		var disposable = createPlan(userId, evaluationId, "support-plan-discard-create");
		var disposableId = UUID.fromString(disposable.path("supportPlanId").asText());
		var discarded = mvc.perform(put("/api/v1/support-plans/{id}/status", disposableId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISCARDED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.status").value("DISCARDED"))
				.andExpect(jsonPath("$.discardedAt").isString())
				.andReturn().getResponse().getContentAsString();
		var discardedReplay = mvc.perform(put("/api/v1/support-plans/{id}/status", disposableId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"DISCARDED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andReturn().getResponse().getContentAsString();
		assertThat(objectMapper.readTree(discardedReplay)).isEqualTo(objectMapper.readTree(discarded));

		var firstHistoryPage = mvc.perform(get("/api/v1/support-plans/history?limit=1").with(user(userId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].supportPlanId")
						.value(disposableId.toString()))
				.andExpect(jsonPath("$.hasMore").value(true))
				.andExpect(jsonPath("$.nextCursor").isString())
				.andReturn().getResponse().getContentAsString();
		var cursor = objectMapper.readTree(firstHistoryPage).path("nextCursor").asText();
		mvc.perform(get("/api/v1/support-plans/history?limit=1&cursor=" + cursor).with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].supportPlanId").value(replacementId.toString()));
		assertThat(jdbc.sql("select count(*) from support_plan_activity_schedule where support_plan_id = :id")
				.param("id", disposableId).query(Long.class).single()).isZero();
	}

	@Test
	void unchangedReplacementReviewKeepsTheCurrentPlanUsable() throws Exception {
		var userId = insertProfile();
		var evaluationId = evaluation(userId, "MINIMAL", "MINIMAL", false);
		var currentDraft = createPlan(userId, evaluationId, "support-plan-unchanged-current");
		var currentId = UUID.fromString(currentDraft.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", currentId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-unchanged-activate"))
				.andExpect(status().isOk());
		var replacement = createPlan(userId, evaluationId, "support-plan-unchanged-draft");
		var replacementId = UUID.fromString(replacement.path("supportPlanId").asText());
		var summaryId = insertReassessmentSummary(userId);
		String request = "{\"currentSupportPlanId\":\"" + currentId
				+ "\",\"currentVersion\":1,\"reassessmentSummaryId\":\"" + summaryId + "\"}";

		mvc.perform(post("/api/v1/support-plans/{id}/replacement-review", replacementId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("CURRENT_PLAN_VALID_NO_BETTER_ALTERNATIVE"))
				.andExpect(jsonPath("$.comparison[0].change").value("UNCHANGED"));
		mvc.perform(post("/api/v1/support-plans/{id}/replace", replacementId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-unchanged-confirm")
				.contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_REPLACEMENT_UNCHANGED"));

		assertThat(jdbc.sql("select status from support_plan where id = :id").param("id", currentId)
				.query(String.class).single()).isEqualTo("ACTIVE");
		assertThat(jdbc.sql("select status from support_plan where id = :id").param("id", replacementId)
				.query(String.class).single()).isEqualTo("DRAFT");
	}

	@Test
	void replacementRejectsSummaryFromADifferentScreeningEpisode() throws Exception {
		var userId = insertProfile();
		var currentEvaluation = evaluation(userId, "MINIMAL", "MINIMAL", false);
		var current = createPlan(userId, currentEvaluation, "support-plan-context-current");
		var currentId = UUID.fromString(current.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", currentId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-context-activate"))
				.andExpect(status().isOk());

		var replacementEvaluation = evaluation(userId, "MILD", "MINIMAL", false);
		var replacement = createPlan(userId, replacementEvaluation, "support-plan-context-draft");
		var replacementId = UUID.fromString(replacement.path("supportPlanId").asText());
		var mismatchedSummaryId = insertReassessmentSummary(userId, Instant.now().minusSeconds(60), "ACTIVE");
		String request = replacementBody(currentId, 1, mismatchedSummaryId);

		mvc.perform(post("/api/v1/support-plans/{id}/replacement-review", replacementId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REASSESSMENT_SCREENING_CONTEXT_MISMATCH"));
		mvc.perform(post("/api/v1/support-plans/{id}/replace", replacementId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-context-confirm")
				.contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REASSESSMENT_SCREENING_CONTEXT_MISMATCH"));

		assertThat(jdbc.sql("select status from support_plan where id = :id").param("id", currentId)
				.query(String.class).single()).isEqualTo("ACTIVE");
		assertThat(jdbc.sql("select status from support_plan where id = :id").param("id", replacementId)
				.query(String.class).single()).isEqualTo("DRAFT");
	}

	@Test
	void staleEntitlementWithdrawnAndUnavailableReviewsLeaveBothPlansUnchanged() throws Exception {
		var userId = insertProfile();
		var currentEvaluation = evaluation(userId, "MINIMAL", "MINIMAL", false);
		var current = createPlan(userId, currentEvaluation, "support-plan-failure-current");
		var currentId = UUID.fromString(current.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", currentId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "support-plan-failure-activate"))
				.andExpect(status().isOk());
		var draftEvaluation = evaluation(userId, "MILD", "MINIMAL", false);
		var draft = createPlan(userId, draftEvaluation, "support-plan-failure-draft");
		var draftId = UUID.fromString(draft.path("supportPlanId").asText());

		var staleSummaryId = insertReassessmentSummary(userId, Instant.now().minusSeconds(48L * 60 * 60));
		String staleRequest = replacementBody(currentId, 1, staleSummaryId);
		mvc.perform(post("/api/v1/support-plans/{id}/replacement-review", draftId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(staleRequest))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REASSESSMENT_SUMMARY_STALE"));

		var summaryId = insertReassessmentSummary(userId);
		String request = replacementBody(currentId, 1, summaryId);
		when(entitlements.current(any(), anyString(), any())).thenReturn(new CurrentEntitlementResponse(userId,
				ServicePackage.FREE, EntitlementSource.DEFAULT_FREE, null, null, null,
				"service-entitlement-v1", 0, Instant.now()));
		mvc.perform(post("/api/v1/support-plans/{id}/replacement-review", draftId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SUPPORT_PLAN_ENTITLEMENT_REQUIRED"));

		when(entitlements.current(any(), anyString(), any())).thenAnswer(invocation -> paid(invocation.getArgument(0)));
		eligible(ResourceEligibilityOutcome.WITHDRAWN, ResourceEligibilityReasonCode.ELIGIBILITY_WITHDRAWN);
		mvc.perform(post("/api/v1/support-plans/{id}/replacement-review", draftId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isConflict());

		eligible(ResourceEligibilityOutcome.UNAVAILABLE, ResourceEligibilityReasonCode.DEPENDENCY_UNAVAILABLE);
		mvc.perform(post("/api/v1/support-plans/{id}/replacement-review", draftId).with(user(userId))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("RESOURCE_ELIGIBILITY_UNAVAILABLE"));

		assertThat(jdbc.sql("select status from support_plan where id = :id").param("id", currentId)
				.query(String.class).single()).isEqualTo("ACTIVE");
		assertThat(jdbc.sql("select status from support_plan where id = :id").param("id", draftId)
				.query(String.class).single()).isEqualTo("DRAFT");
		assertThat(jdbc.sql("select count(*) from support_plan_command where command_type = 'REPLACE' and user_id = :id")
				.param("id", userId).query(Long.class).single()).isZero();
	}

	@Test
	void concurrentReplacementConfirmationsCommitExactlyOneTransition() throws Exception {
		var userId = insertProfile();
		var currentEvaluation = evaluation(userId, "MINIMAL", "MINIMAL", false);
		var current = createPlan(userId, currentEvaluation, "support-plan-concurrent-replace-current");
		var currentId = UUID.fromString(current.path("supportPlanId").asText());
		mvc.perform(post("/api/v1/support-plans/{id}/activate", currentId).with(user(userId))
				.header("If-Match", "\"0\"")
				.header("Idempotency-Key", "support-plan-concurrent-replace-activate"))
				.andExpect(status().isOk());
		var draftEvaluation = evaluation(userId, "MILD", "MINIMAL", false);
		var draft = createPlan(userId, draftEvaluation, "support-plan-concurrent-replace-draft");
		var draftId = UUID.fromString(draft.path("supportPlanId").asText());
		var summaryId = insertReassessmentSummary(userId);
		String request = replacementBody(currentId, 1, summaryId);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> replaceAfter(start, userId, draftId, request,
					"support-plan-concurrent-replace-first"));
			var second = executor.submit(() -> replaceAfter(start, userId, draftId, request,
					"support-plan-concurrent-replace-second"));
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(200, 412);
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(jdbc.sql("select status from support_plan where id = :id").param("id", currentId)
				.query(String.class).single()).isEqualTo("SUPERSEDED");
		assertThat(jdbc.sql("select status from support_plan where id = :id").param("id", draftId)
				.query(String.class).single()).isEqualTo("ACTIVE");
		assertThat(jdbc.sql("select count(*) from support_plan_command where command_type = 'REPLACE' and user_id = :id")
				.param("id", userId).query(Long.class).single()).isEqualTo(1);
	}

	private int activateAfter(CountDownLatch start, UUID userId, UUID planId, String key) throws Exception {
		start.await(5, TimeUnit.SECONDS);
		return mvc.perform(post("/api/v1/support-plans/{id}/activate", planId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", key))
				.andReturn().getResponse().getStatus();
	}

	private int transitionAfter(CountDownLatch start, UUID userId, UUID planId, String status) throws Exception {
		start.await(5, TimeUnit.SECONDS);
		return mvc.perform(put("/api/v1/support-plans/{id}/status", planId).with(user(userId))
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"" + status + "\"}"))
				.andReturn().getResponse().getStatus();
	}

	private int engagementAfter(CountDownLatch start, UUID userId, UUID occurrenceId, String body) throws Exception {
		start.await(5, TimeUnit.SECONDS);
		return mvc.perform(put("/api/v1/support-plan-occurrences/{id}/engagement", occurrenceId)
				.with(user(userId)).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
				.content(body)).andReturn().getResponse().getStatus();
	}

	private com.fasterxml.jackson.databind.JsonNode createPlan(UUID userId, UUID evaluationId, String key)
			throws Exception {
		var response = mvc.perform(post("/api/v1/support-plans").with(user(userId))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body(evaluationId)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response);
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

	private ResourceProposal resourceProposal(UUID proposalId, UUID userId, UUID specialistId,
			String resourceId) {
		return new ResourceProposal(proposalId, 1, UUID.randomUUID(), userId, specialistId,
				UUID.randomUUID(), 1, UUID.randomUUID(), UUID.fromString(resourceId), "1",
				"TRY_ALTERNATIVE_RESOURCE", "Tài nguyên hỗ trợ được đề xuất",
				"Đề xuất sau nội dung đã trao đổi.", "session-summary-v1",
				Instant.parse("2026-10-02T02:00:00Z"));
	}

	private UUID evaluation(UUID userId, String phqLevel, String gadLevel, boolean positive) throws Exception {
		var phq9 = insertAssessment(userId, PHQ9_DEFINITION, "PHQ9", phqLevel, positive);
		var gad7 = insertAssessment(userId, GAD7_DEFINITION, "GAD7", gadLevel, false);
		var response = mvc.perform(post("/api/v2/support-evaluations").with(user(userId))
				.header("Idempotency-Key", "support-evaluation-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"phq9AssessmentId\":\"" + phq9 + "\",\"gad7AssessmentId\":\"" + gad7 + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		var evaluationId = UUID.fromString(objectMapper.readTree(response).path("supportEvaluationId").asText());
		jdbc.sql("""
				insert into screening_episode
				(id,user_id,purpose,status,phq9_assessment_id,gad7_assessment_id,support_evaluation_id,
				 created_at,updated_at,completed_at,version)
				values (:id,:userId,'INITIAL_CHECK','COMPLETED',:phq9,:gad7,:evaluation,now(),now(),now(),0)
				""").param("id", UUID.randomUUID()).param("userId", userId).param("phq9", phq9)
				.param("gad7", gad7).param("evaluation", evaluationId).update();
		return evaluationId;
	}

	private UUID insertProfile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id,display_name) values (:id,'SupportPlan test user')")
				.param("id", userId).update();
		return userId;
	}

	private int replaceAfter(CountDownLatch start, UUID userId, UUID draftId, String body, String key)
			throws Exception {
		start.await(5, TimeUnit.SECONDS);
		return mvc.perform(post("/api/v1/support-plans/{id}/replace", draftId).with(user(userId))
				.header("If-Match", "\"0\"").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn().getResponse().getStatus();
	}

	private UUID insertReassessmentSummary(UUID userId) throws Exception {
		return insertReassessmentSummary(userId, Instant.now().minusSeconds(60));
	}

	private UUID insertReassessmentSummary(UUID userId, Instant end) throws Exception {
		return insertReassessmentSummary(userId, end, "DRAFT");
	}

	private UUID insertReassessmentSummary(UUID userId, Instant end, String planStatus) throws Exception {
		var id = UUID.randomUUID();
		var jobId = UUID.randomUUID();
		var currentStart = end.minusSeconds(14L * 24 * 60 * 60);
		var previousStart = currentStart.minusSeconds(14L * 24 * 60 * 60);
		var snapshot = objectMapper.createObjectNode();
		snapshot.put("summaryId", id.toString()).put("summaryVersion", "reassessment-summary-v2")
				.put("composedAt", end.toString()).put("disclaimerCode", "FOUR_DIMENSIONS_NOT_COMBINED");
		snapshot.putObject("previousPeriod").put("startAt", previousStart.toString())
				.put("endAt", currentStart.toString());
		snapshot.putObject("currentPeriod").put("startAt", currentStart.toString()).put("endAt", end.toString());
		var draftEvidence = jdbc.sql("""
				select evaluation.phq9_assessment_id, evaluation.gad7_assessment_id
				from support_plan plan
				join support_evaluation_v2 evaluation on evaluation.id = plan.support_evaluation_id
				where plan.user_id = :userId and plan.status = :planStatus
				""").param("userId", userId).param("planStatus", planStatus).query((row, number) -> List.of(
				row.getObject("phq9_assessment_id", UUID.class),
				row.getObject("gad7_assessment_id", UUID.class))).single();
		var trends = snapshot.putObject("screening").put("state", "INSUFFICIENT_DATA").putArray("trends");
		for (int index = 0; index < 2; index++) {
			var trend = trends.addObject().put("instrument", index == 0 ? "PHQ9" : "GAD7")
					.put("state", "INSUFFICIENT_DATA").put("scoringVersion", "test-v1");
			trend.putNull("previous");
			trend.putObject("current").put("assessmentId", draftEvidence.get(index).toString())
					.put("questionnaireVersion", "test-v1").put("submittedAt", end.toString())
					.put("totalScore", 0).put("screeningLevel", "MINIMAL");
			trend.putNull("rawDelta").put("direction", "INSUFFICIENT_DATA");
		}
		var journal = snapshot.putObject("journalContext");
		journal.put("state", "UNAVAILABLE").put("unavailableReason", "DEPENDENCY_UNAVAILABLE")
				.put("jobId", jobId.toString());
		journal.putNull("analysisId");
		for (String field : List.of("sourceJournalRevisions", "contextSignals", "emotionIndicators",
				"recurringThemes", "changesComparedWithPreviousPeriod", "preferences", "barriers",
				"helpfulPatterns")) journal.putArray(field);
		journal.putNull("dataCoverage").putNull("provenance");
		var engagement = snapshot.putObject("supportPlanEngagement").put("state", "INSUFFICIENT_DATA");
		engagement.putObject("previousPeriod").put("completedCount", 0).put("skippedCount", 0);
		engagement.putObject("currentPeriod").put("completedCount", 0).put("skippedCount", 0);
		engagement.putArray("sources");
		snapshot.putObject("selfReportedExperience").put("state", "INSUFFICIENT_DATA")
				.put("unavailableReason", "NOT_PROVIDED").putNull("source");
		snapshot.putObject("activityReflection").put("state", "INSUFFICIENT_DATA").putArray("sources");
		snapshot.putNull("userReflection");
		jdbc.sql("""
				insert into reassessment_summary
				(id,user_id,idempotency_key,request_hash,summary_version,journal_job_id,
				 previous_period_start,previous_period_end,current_period_start,current_period_end,
				 snapshot,composed_at)
				values (:id,:userId,:key,:hash,'reassessment-summary-v2',:jobId,
				 :previousStart,:currentStart,:currentStart,:end,cast(:snapshot as jsonb),:end)
				""").param("id", id).param("userId", userId).param("key", "reassessment-" + UUID.randomUUID())
				.param("hash", "1".repeat(64)).param("jobId", jobId)
				.param("previousStart", Timestamp.from(previousStart))
				.param("currentStart", Timestamp.from(currentStart)).param("end", Timestamp.from(end))
				.param("snapshot", objectMapper.writeValueAsString(snapshot)).update();
		return id;
	}

	private String replacementBody(UUID currentId, long currentVersion, UUID summaryId) {
		return "{\"currentSupportPlanId\":\"" + currentId + "\",\"currentVersion\":" + currentVersion
				+ ",\"reassessmentSummaryId\":\"" + summaryId + "\"}";
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
