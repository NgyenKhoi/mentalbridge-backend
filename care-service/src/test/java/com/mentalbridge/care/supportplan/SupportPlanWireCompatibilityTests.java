package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.ResourceDraft;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SlotDraft;

class SupportPlanWireCompatibilityTests {

	private static final UUID USER = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID PLAN = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID ACTION = UUID.fromString("10000000-0000-0000-0000-000000000003");
	private static final UUID OCCURRENCE = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID SCHEDULE = UUID.fromString("10000000-0000-0000-0000-000000000005");
	private static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");
	private static final LocalDate TODAY = LocalDate.parse("2026-10-17");
	private static final List<String> ENGAGEMENT_FIELDS = List.of("hidden", "helpfulness", "barrierCode",
			"reflection", "summaryReuseApproved", "engagementUpdatedAt");
	private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final SupportPlanSlotRepository slots = mock(SupportPlanSlotRepository.class);
	private final SupportPlanFocusRepository focuses = mock(SupportPlanFocusRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final SupportPlanActivityOccurrenceService activities = mock(SupportPlanActivityOccurrenceService.class);
	private final SupportPlanV3Service service = new SupportPlanV3Service(plans,
			mock(SupportPlanTemplateFamilyRepository.class), slots, focuses, actions,
			mock(SupportPlanActivityScheduleRepository.class), activities, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void earlierNativePoliciesOmitCycleAndKeepRequiredNullableLifecycleFields() {
		for (String policy : List.of("support-plan-v3-actions-v1", NativeSupportPlanWriter.EMOTION_POLICY,
				NativeSupportPlanWriter.POLICY)) {
			var plan = nativePlan(policy);
			stubNativeRead(plan);
			var json = mapper.valueToTree(service.get(USER, PLAN));
			assertThat(json.has("cycle")).isFalse();
			assertThat(json.has("activatedAt")).isTrue();
			assertThat(json.get("activatedAt").isNull()).isTrue();
			assertThat(json.has("completionReason")).isTrue();
			assertThat(json.get("completionReason").isNull()).isTrue();
		}
	}

	@Test
	void legacyAdapterOmitsCycle() {
		var plan = legacyPlan();
		when(plans.findByIdAndUserId(PLAN, USER)).thenReturn(Optional.of(plan));
		when(slots.findBySupportPlanIdOrderByOrdinal(PLAN)).thenReturn(List.of(slot()));
		assertThat(mapper.valueToTree(service.get(USER, PLAN)).has("cycle")).isFalse();
	}

	@Test
	void proposalCycleIsPresentInDraftAndAfterActivation() {
		var plan = nativePlan(NativeSupportPlanWriter.PROPOSAL_POLICY);
		plan.initializeCycle("Asia/Ho_Chi_Minh");
		stubNativeRead(plan);
		var draft = mapper.valueToTree(service.get(USER, PLAN)).get("cycle");
		assertThat(draft.get("state").asText()).isEqualTo("NOT_STARTED");
		assertThat(draft.get("startedOn").isNull()).isTrue();
		plan.activate(NOW);
		var active = mapper.valueToTree(service.get(USER, PLAN)).get("cycle");
		assertThat(active.get("state").asText()).isEqualTo("IN_PROGRESS");
		assertThat(active.get("startedOn").asText()).isEqualTo("2026-10-17");
		assertThat(active.get("reviewDueOn").asText()).isEqualTo("2026-10-30");
	}

	@Test
	void existingNativeOccurrenceIsByteIdenticalWithoutEngagement() throws Exception {
		var json = occurrenceJson(nativePlan(NativeSupportPlanWriter.POLICY), occurrence(true));
		assertThat(json).isEqualTo(originalOccurrenceJson("NATIVE_V3"));
	}

	@Test
	void historicalStateOnlyTimestampDoesNotAddEngagementFields() throws Exception {
		var occurrence = occurrence(true);
		ReflectionTestUtils.setField(occurrence, "engagementUpdatedAt", NOW);
		ReflectionTestUtils.setField(occurrence, "version", 2L);
		var json = occurrenceJson(nativePlan(NativeSupportPlanWriter.POLICY), occurrence);
		assertThat(json).isEqualTo(originalOccurrenceJson("NATIVE_V3").replace("\"version\":0", "\"version\":2"));
	}

	@Test
	void adapterOccurrenceRemainsByteIdenticalEvenWithLegacyEngagement() throws Exception {
		var occurrence = occurrence(false);
		occurrence.replaceEngagement("SCHEDULED", true, null, null, "Private reflection", false, NOW);
		var json = occurrenceJson(legacyPlan(), occurrence);
		assertThat(json).isEqualTo(originalOccurrenceJson("V2_RESOURCE_ADAPTER"));
	}

	@Test
	void nativeRecordedEngagementIncludesFalseFlagsAndPrivateOwnerMetadata() throws Exception {
		var occurrence = occurrence(true);
		occurrence.replaceNativeEngagement(false, null, null, "Private reflection", false, NOW);
		var json = mapper.readTree(occurrenceJson(nativePlan(NativeSupportPlanWriter.PROPOSAL_POLICY), occurrence));
		assertThat(json.get("hidden").asBoolean()).isFalse();
		assertThat(json.get("summaryReuseApproved").asBoolean()).isFalse();
		assertThat(json.get("reflection").asText()).isEqualTo("Private reflection");
		assertThat(json.get("engagementUpdatedAt").asText()).isEqualTo(NOW.toString());
		assertThat(json.has("helpfulness")).isFalse();
	}

	private String occurrenceJson(SupportPlanEntity plan, SupportPlanActivityOccurrenceEntity occurrence) throws Exception {
		when(activities.loadV3Window(USER, TODAY, TODAY))
				.thenReturn(new SupportPlanActivityOccurrenceService.V3Window(plan, List.of(occurrence)));
		when(slots.findBySupportPlanIdOrderByOrdinal(PLAN)).thenReturn(List.of(slot()));
		var value = service.occurrences(USER, TODAY, TODAY).occurrences().get(0);
		String json = mapper.writeValueAsString(value);
		if (occurrence.sourceActionId() == null || !occurrence.hasEngagement()) {
			for (String field : ENGAGEMENT_FIELDS) assertThat(mapper.readTree(json).has(field)).isFalse();
		}
		return json;
	}

	private String originalOccurrenceJson(String representation) {
		return "{\"occurrenceId\":\"" + OCCURRENCE + "\",\"actionId\":\"" + ACTION
				+ "\",\"actionType\":\"RESOURCE\",\"actionVersion\":1,\"scheduleId\":\"" + SCHEDULE
				+ "\",\"scheduleVersion\":1,\"representationMode\":\"" + representation
				+ "\",\"version\":0,\"localDate\":\"2026-10-17\",\"scheduledAt\":\"2026-10-17T08:00:00Z\","
				+ "\"state\":\"SCHEDULED\",\"displayState\":\"SCHEDULED\",\"stateReason\":null,"
				+ "\"completionEvidence\":null,\"outcomeRecordedAt\":null}";
	}

	private SupportPlanActivityOccurrenceEntity occurrence(boolean nativeAction) {
		var value = new SupportPlanActivityOccurrenceEntity();
		ReflectionTestUtils.setField(value, "id", OCCURRENCE);
		ReflectionTestUtils.setField(value, "activityScheduleId", SCHEDULE);
		ReflectionTestUtils.setField(value, "scheduleVersion", 1);
		ReflectionTestUtils.setField(value, "sourcePlanVersion", 1L);
		ReflectionTestUtils.setField(value, "sourceSlotKey", "core");
		ReflectionTestUtils.setField(value, "localDate", TODAY);
		ReflectionTestUtils.setField(value, "scheduledAt", NOW);
		ReflectionTestUtils.setField(value, "state", "SCHEDULED");
		if (nativeAction) {
			ReflectionTestUtils.setField(value, "sourceActionId", ACTION);
			ReflectionTestUtils.setField(value, "sourceActionVersion", 1L);
			ReflectionTestUtils.setField(value, "sourceActionType", "RESOURCE");
		}
		return value;
	}

	private SupportPlanSlotEntity slot() {
		return new SupportPlanSlotEntity(ACTION, PLAN, 1,
				new SlotDraft("core", "CORE", "DEPRESSIVE_SYMPTOMS", "SUPPORT",
						new ResourceDraft(UUID.randomUUID(), 1, UUID.randomUUID(), "PRIMARY", "ARTICLE",
								"Reviewed resource", "Summary", null), List.of()));
	}

	private SupportPlanEntity legacyPlan() {
		return new SupportPlanEntity(PLAN, USER, UUID.randomUUID(), "mb-support-routing-capstone-v2",
				NOW, "content-eligibility-v1", NOW, "PLUS", "PAID", "service-entitlement-v1", 1,
				NOW, "Plan rationale", "NEGATIVE_SAFETY_SCREEN", "NO_TRIGGER", "safety-v1",
				"SAFETY", "Safety guidance", 1, NOW);
	}

	private SupportPlanEntity nativePlan(String policy) {
		return SupportPlanEntity.nativeDraft(PLAN, USER, UUID.randomUUID(), "mb-support-routing-capstone-v2",
				NOW, policy, "PLUS", "PAID", "service-entitlement-v1", 1, NOW,
				"DOMAIN_AWARE_WELLBEING_SUPPORT", "Plan rationale", "NEGATIVE_SAFETY_SCREEN", "NO_TRIGGER",
				"safety-v1", "SAFETY", "Safety guidance", 1, NOW);
	}

	private void stubNativeRead(SupportPlanEntity plan) {
		UUID focusId = UUID.randomUUID();
		when(plans.findByIdAndUserId(PLAN, USER)).thenReturn(Optional.of(plan));
		when(focuses.findBySupportPlanIdAndUserIdOrderByOrdinal(PLAN, USER)).thenReturn(List.of(
				new SupportPlanFocusEntity(focusId, PLAN, USER, 1, "Support focus", null, NOW)));
		var action = new PlanActionEntity(ACTION, PLAN, focusId, USER, 1, "JOURNAL", "REFLECT", null,
				null, new PlanActionSourcePolicy.Provenance("SYSTEM_POLICY", "policy", "1", NOW),
				new PlanActionEntity.Presentation("Reflect", null, null, null), NOW);
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(PLAN, USER))
				.thenReturn(List.of(action));
	}
}
