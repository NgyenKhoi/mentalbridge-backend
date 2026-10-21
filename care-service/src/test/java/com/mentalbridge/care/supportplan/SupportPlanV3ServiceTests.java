package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
	private final SupportPlanTemplateFamilyRepository families = mock(SupportPlanTemplateFamilyRepository.class);
	private final SupportPlanSlotRepository slots = mock(SupportPlanSlotRepository.class);
	private final SupportPlanFocusRepository focuses = mock(SupportPlanFocusRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final SupportPlanActivityScheduleRepository schedules = mock(SupportPlanActivityScheduleRepository.class);
	private final SupportPlanActivityOccurrenceService activities = mock(SupportPlanActivityOccurrenceService.class);
	private final SupportPlanV3Service service = new SupportPlanV3Service(plans, families, slots, focuses, actions,
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
		assertThat(view.safety().status()).isEqualTo("NEGATIVE_SAFETY_SCREEN");
		assertThat(view.focuses()).singleElement().satisfies(focus -> {
			assertThat(focus.title()).isEqualTo("Hỗ trợ tâm trạng");
			assertThat(focus.actions()).singleElement().satisfies(action -> {
				assertThat(action.actionId()).isEqualTo(slot.id().toString());
				assertThat(action.actionType()).isEqualTo("RESOURCE");
				assertThat(action.pathRole()).isEqualTo("PRIMARY");
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
	void makesProfessionalSupportPrimaryAndSelfHelpAdjunctForModerateOrHigherRouting() {
		UUID userId = UUID.randomUUID();
		UUID planId = UUID.randomUUID();
		var plan = legacyPlan(planId, userId);
		var resource = new ResourceDraft(UUID.randomUUID(), 3, UUID.randomUUID(), "ADJUNCT", "ARTICLE",
				"Nội dung hỗ trợ thêm", "Một lựa chọn tự hỗ trợ đi kèm", null);
		var slot = new SupportPlanSlotEntity(UUID.randomUUID(), planId, 1,
				new SlotDraft("depression-adjunct", "OPTIONAL", "DEPRESSIVE_SYMPTOMS",
						"DEPRESSION_SUPPORT_ADJUNCT", resource, List.of()));

		when(plans.findByIdAndUserId(planId, userId)).thenReturn(Optional.of(plan));
		when(families.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of(
				new SupportPlanTemplateFamilyEntity(UUID.randomUUID(), planId, 1,
						"DEPRESSIVE_PROFESSIONAL_ADJUNCT", "DEPRESSIVE_SYMPTOMS")));
		when(slots.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of(slot));
		when(schedules.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of());

		var view = service.get(userId, planId);

		assertThat(view.focuses()).singleElement().satisfies(focus -> {
			assertThat(focus.actions()).hasSize(2);
			assertThat(focus.actions().get(0).actionType()).isEqualTo("PROFESSIONAL_SUPPORT");
			assertThat(focus.actions().get(0).pathRole()).isEqualTo("PRIMARY");
			assertThat(focus.actions().get(0).source().ownerDomain()).isEqualTo("CONSULTATION");
			assertThat(focus.actions().get(1).actionType()).isEqualTo("RESOURCE");
			assertThat(focus.actions().get(1).pathRole()).isEqualTo("ADJUNCT");
		});
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
					assertThat(value.pathRole()).isEqualTo("PRIMARY");
					assertThat(value.source().ownerDomain()).isEqualTo("JOURNAL_AI");
					assertThat(value.source().sourceKind()).isEqualTo("JOURNAL_TEMPLATE");
					assertThat(value.presentation().title()).isEqualTo("Ghi lại điều đáng nhớ");
				}));
	}

	@Test
	void derivesNativePathRolesFromProfessionalSupportContextRatherThanPurposeCode() {
		UUID userId = UUID.randomUUID();
		UUID planId = UUID.randomUUID();
		UUID focusId = UUID.randomUUID();
		var plan = SupportPlanEntity.nativeDraft(planId, userId, UUID.randomUUID(),
				"mb-support-routing-capstone-v2", NOW, "support-plan-v3-actions-v1", "PLUS", "PAID",
				"service-entitlement-v1", 3, NOW, "DOMAIN_AWARE_WELLBEING_SUPPORT", "Plan context",
				"NEGATIVE_SAFETY_SCREEN", "NO_SAFETY_SIGNAL", "safety-v1", "STANDARD_SAFETY_REMINDER",
				"Safety guidance", 2, NOW);
		var focus = new SupportPlanFocusEntity(focusId, planId, userId, 1, "Support focus", null, NOW);
		var professional = new PlanActionEntity(UUID.randomUUID(), planId, focusId, userId, 1,
				"PROFESSIONAL_SUPPORT", "CONNECT_WITH_PROFESSIONAL", null,
				new SourceReference("CONSULTATION", "PROFESSIONAL_SUPPORT_PATHWAY", "specialist-discovery", "1",
						"support-plan-professional-path-v1", null, null, NOW),
				new Provenance("SYSTEM_POLICY", "routing", "1", NOW),
				new Presentation("Connect with a specialist", null, null, null), NOW);
		var resource = new PlanActionEntity(UUID.randomUUID(), planId, focusId, userId, 2,
				"RESOURCE", "READ_CONTENT", null,
				new SourceReference("CONTENT", "RESOURCE_VERSION", UUID.randomUUID().toString(), "1", null,
						UUID.randomUUID(), "content-eligibility-v1", NOW),
				new Provenance("SYSTEM_POLICY", "routing", "1", NOW),
				new Presentation("Read reviewed content", null, null, null), NOW);

		when(plans.findByIdAndUserId(planId, userId)).thenReturn(Optional.of(plan));
		when(focuses.findBySupportPlanIdAndUserIdOrderByOrdinal(planId, userId)).thenReturn(List.of(focus));
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(planId, userId))
				.thenReturn(List.of(professional, resource));
		when(schedules.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of());

		var view = service.get(userId, planId);

		assertThat(view.focuses()).singleElement().satisfies(result -> {
			assertThat(result.actions()).extracting(SupportPlanV3Service.PlanActionView::purposeCode)
					.containsExactly("CONNECT_WITH_PROFESSIONAL", "READ_CONTENT");
			assertThat(result.actions()).extracting(SupportPlanV3Service.PlanActionView::pathRole)
					.containsExactly("PRIMARY", "ADJUNCT");
		});
	}

	@Test
	void projectionKeepsEachOccurrenceZoneAndPlanWeekZoneNearMidnight() {
		Instant midnight = Instant.parse("2026-10-21T15:15:00Z");
		LocalDate date = LocalDate.parse("2026-10-21");
		UUID userId = UUID.randomUUID();
		UUID planId = UUID.randomUUID();
		UUID focusId = UUID.randomUUID();
		var plan = SupportPlanEntity.nativeDraft(planId, userId, UUID.randomUUID(),
				"mb-support-routing-capstone-v2", NOW, "support-plan-v3-actions-v1", "PLUS", "PAID",
				"service-entitlement-v1", 3, NOW, "DOMAIN_AWARE_WELLBEING_SUPPORT", "Plan context",
				"NEGATIVE_SAFETY_SCREEN", "NO_SAFETY_SIGNAL", "safety-v1", "STANDARD_SAFETY_REMINDER",
				"Safety guidance", 2, NOW);
		plan.activate(NOW);
		var focus = new SupportPlanFocusEntity(focusId, planId, userId, 1, "Resources", null, NOW);
		var actionList = new java.util.ArrayList<PlanActionEntity>();
		var scheduleList = new java.util.ArrayList<SupportPlanActivityScheduleEntity>();
		var occurrenceList = new java.util.ArrayList<SupportPlanActivityOccurrenceEntity>();
		for (String zone : List.of("America/Los_Angeles", "Asia/Tokyo")) {
			var action = new PlanActionEntity(UUID.randomUUID(), planId, focusId, userId, actionList.size() + 1,
					"RESOURCE", "READ_CONTENT", null,
					new SourceReference("CONTENT", "RESOURCE_VERSION", UUID.randomUUID().toString(), "7", null,
							UUID.randomUUID(), "content-eligibility-v1", NOW),
					new Provenance("USER_SELECTED", null, null, NOW), new Presentation("Read", null, null, null), NOW);
			action.activate(NOW);
			actionList.add(action);
			scheduleList.add(new SupportPlanActivityScheduleEntity(UUID.randomUUID(), planId, userId,
					actionList.size(), plan.version(), action, "support-plan-activity-schedule-v2", "DAILY", null,
					LocalTime.MIDNIGHT, zone, date, false, NOW));
			var occurrence = mock(SupportPlanActivityOccurrenceEntity.class);
			when(occurrence.id()).thenReturn(UUID.randomUUID());
			when(occurrence.sourceActionId()).thenReturn(action.id());
			when(occurrence.timezone()).thenReturn(zone);
			when(occurrence.state()).thenReturn("SCHEDULED");
			when(occurrence.scheduledAt()).thenReturn(date.atStartOfDay(ZoneId.of(zone)).toInstant());
			occurrenceList.add(occurrence);
		}
		when(plans.findByUserIdAndStatusInForUpdate(userId, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.of(plan));
		when(focuses.findBySupportPlanIdAndUserIdOrderByOrdinal(planId, userId)).thenReturn(List.of(focus));
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(planId, userId)).thenReturn(actionList);
		when(schedules.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(scheduleList);
		when(activities.loadV3Window(userId, date, date)).thenReturn(new SupportPlanActivityOccurrenceService.V3Window(plan, occurrenceList));
		when(activities.planTimezone(plan)).thenReturn("America/Los_Angeles");
		var midnightService = new SupportPlanV3Service(plans, families, slots, focuses, actions, schedules, activities,
				Clock.fixed(midnight, ZoneOffset.UTC));

		var projection = midnightService.resourceActions(userId, date);

		assertThat(projection.timeZone()).isEqualTo("America/Los_Angeles");
		assertThat(projection.actions()).extracting(action -> action.today().timeZone())
				.containsExactly("America/Los_Angeles", "Asia/Tokyo");
		assertThat(midnight.atZone(ZoneId.of(projection.actions().get(0).today().timeZone())).toLocalDate()).isEqualTo(date);
		assertThat(midnight.atZone(ZoneId.of(projection.actions().get(1).today().timeZone())).toLocalDate()).isEqualTo(date.plusDays(1));
	}

	@Test
	void projectionAdaptsV2WithoutOccurrencesAndAllowsNoPlan() {
		UUID userId = UUID.randomUUID();
		UUID planId = UUID.randomUUID();
		var date = java.time.LocalDate.parse("2026-10-11");
		assertThat(service.resourceActions(userId, date).planState()).isEqualTo("NONE");
		var plan = legacyPlan(planId, userId);
		plan.activate(NOW);
		var resource = new ResourceDraft(UUID.randomUUID(), 7, UUID.randomUUID(), "PRIMARY", "ARTICLE",
				"Resource", "Private summary", null);
		var slot = new SupportPlanSlotEntity(UUID.randomUUID(), planId, 1,
				new SlotDraft("core", "CORE", "DEPRESSIVE_SYMPTOMS", "BUILD_ROUTINE", resource, List.of()));
		when(plans.findByUserIdAndStatusInForUpdate(userId, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.of(plan));
		when(slots.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of(slot));
		when(activities.planTimezone(plan)).thenReturn("Asia/Ho_Chi_Minh");
		var result = service.resourceActions(userId, date);
		assertThat(result.representationMode()).isEqualTo("V2_RESOURCE_ADAPTER");
		assertThat(result.actions()).singleElement().satisfies(action -> {
			assertThat(action.resource().resourceVersion()).isEqualTo("7");
			assertThat(action.today()).isNull();
			assertThat(action.schedule()).isNull();
		});
		org.mockito.Mockito.verify(activities, org.mockito.Mockito.never()).loadV3Window(
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	private SupportPlanEntity legacyPlan(UUID planId, UUID userId) {
		return new SupportPlanEntity(planId, userId, UUID.randomUUID(), "mb-support-routing-capstone-v2",
				NOW, "content-eligibility-v1", NOW, "PLUS", "PAID", "service-entitlement-v1", 2,
				NOW, "Lý do kế hoạch", "NEGATIVE_SAFETY_SCREEN", "NO_SAFETY_SIGNAL", "safety-v1",
				"STANDARD_SAFETY_REMINDER", "Hướng dẫn an toàn", 1, NOW);
	}
}
