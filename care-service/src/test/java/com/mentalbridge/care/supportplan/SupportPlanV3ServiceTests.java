package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.supportplan.PlanActionEntity.Presentation;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.ResourceDraft;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SlotDraft;

class SupportPlanV3ServiceTests {

	private static final Instant NOW = Instant.parse("2026-10-11T08:00:00Z");

	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final SupportPlanSlotRepository slots = mock(SupportPlanSlotRepository.class);
	private final SupportPlanFocusRepository focuses = mock(SupportPlanFocusRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final SupportPlanActivityScheduleRepository schedules = mock(SupportPlanActivityScheduleRepository.class);
	private final SupportPlanActivityOccurrenceService activities = mock(SupportPlanActivityOccurrenceService.class);
	private final SupportPlanV3Service service = new SupportPlanV3Service(plans, slots, focuses, actions,
			schedules, activities, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

	@Test
	void adaptsV2SlotsWithoutCreatingNativeActionsOrLosingExactResourceProvenance() {
		UUID userId = UUID.randomUUID();
		UUID planId = UUID.randomUUID();
		UUID resourceId = UUID.randomUUID();
		UUID publicationId = UUID.randomUUID();
		var plan = legacyPlan(planId, userId);
		plan.activate(NOW);
		var resource = new ResourceDraft(resourceId, 7, publicationId, "PRIMARY", "ARTICLE",
				"Một bước nhỏ", "Tóm tắt đã duyệt", "https://example.test/resource");
		var slot = new SupportPlanSlotEntity(UUID.randomUUID(), planId, 1,
				new SlotDraft("depressive-core", "CORE", "DEPRESSIVE_SYMPTOMS", "BUILD_ROUTINE",
						resource, List.of()));

		when(plans.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.of(plan));
		when(slots.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of(slot));
		when(schedules.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of());

		var view = service.current(userId);

		assertThat(view.modelVersion()).isEqualTo(3);
		assertThat(view.planPolicyVersion()).isEqualTo(SupportPlanService.SELECTION_POLICY_VERSION);
		assertThat(view.representationMode()).isEqualTo("V2_RESOURCE_ADAPTER");
		assertThat(view.focuses()).singleElement().satisfies(focus -> {
			assertThat(focus.title()).isEqualTo("Hỗ trợ tâm trạng");
			assertThat(focus.actions()).singleElement().satisfies(action -> {
				assertThat(action.actionId()).isEqualTo(slot.id().toString());
				assertThat(action.actionType()).isEqualTo("RESOURCE");
				assertThat(action.representationMode()).isEqualTo("V2_RESOURCE_ADAPTER");
				assertThat(action.source().ownerDomain()).isEqualTo("CONTENT");
				assertThat(action.source().sourceId()).isEqualTo(resourceId.toString());
				assertThat(action.source().sourceRevision()).isEqualTo("7");
				assertThat(action.source().publicationId()).isEqualTo(publicationId);
			});
		});
		assertThat(focuses.findBySupportPlanIdAndUserIdOrderByOrdinal(planId, userId)).isEmpty();
		assertThat(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(planId, userId)).isEmpty();
	}

	@Test
	void returnsNativeTypedActionsWithBoundedSourceAndProvenance() {
		UUID userId = UUID.randomUUID();
		UUID planId = UUID.randomUUID();
		UUID focusId = UUID.randomUUID();
		var plan = SupportPlanEntity.nativeDraft(planId, userId, UUID.randomUUID(),
				"mb-support-routing-capstone-v2", NOW, "support-plan-v3-actions-v1", "PLUS", "PAID",
				"service-entitlement-v1", 3, NOW, "DOMAIN_AWARE_WELLBEING_SUPPORT", "Lý do kế hoạch",
				"NEGATIVE_SAFETY_SCREEN", "NO_SAFETY_SIGNAL", "safety-v1", "STANDARD_SAFETY_REMINDER",
				"Hướng dẫn an toàn", 1, NOW);
		var focus = new SupportPlanFocusEntity(focusId, planId, userId, 1, "Nhịp sinh hoạt", null, NOW);
		var action = new PlanActionEntity(UUID.randomUUID(), planId, focusId, userId, 1,
				"JOURNAL", "REFLECT", null,
				new SourceReference("JOURNAL_AI", "JOURNAL_TEMPLATE", "evening-reflection", "2",
						"journal-template-v2", null, null, NOW),
				new Provenance("USER_SELECTED", null, null, NOW),
				new Presentation("Ghi lại điều đáng nhớ", null, null, null), NOW);

		when(plans.findByIdAndUserId(planId, userId)).thenReturn(Optional.of(plan));
		when(focuses.findBySupportPlanIdAndUserIdOrderByOrdinal(planId, userId)).thenReturn(List.of(focus));
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(planId, userId))
				.thenReturn(List.of(action));
		when(schedules.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of());

		var view = service.get(userId, planId);

		assertThat(view.planPolicyVersion()).isEqualTo("support-plan-v3-actions-v1");
		assertThat(view.representationMode()).isEqualTo("NATIVE_V3");
		assertThat(view.focuses()).singleElement().satisfies(result ->
				assertThat(result.actions()).singleElement().satisfies(value -> {
					assertThat(value.actionType()).isEqualTo("JOURNAL");
					assertThat(value.source().ownerDomain()).isEqualTo("JOURNAL_AI");
					assertThat(value.source().sourceKind()).isEqualTo("JOURNAL_TEMPLATE");
					assertThat(value.presentation().title()).isEqualTo("Ghi lại điều đáng nhớ");
				}));
	}

	private SupportPlanEntity legacyPlan(UUID planId, UUID userId) {
		return new SupportPlanEntity(planId, userId, UUID.randomUUID(), "mb-support-routing-capstone-v2",
				NOW, "content-eligibility-v1", NOW, "PLUS", "PAID", "service-entitlement-v1", 2,
				NOW, "Lý do kế hoạch", "NEGATIVE_SAFETY_SCREEN", "NO_SAFETY_SIGNAL", "safety-v1",
				"STANDARD_SAFETY_REMINDER", "Hướng dẫn an toàn", 1, NOW);
	}
}
