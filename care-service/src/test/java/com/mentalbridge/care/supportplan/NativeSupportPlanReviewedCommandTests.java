package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.profile.UserProfileEntity;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.PlanActionEntity.Presentation;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;

class NativeSupportPlanReviewedCommandTests {
	private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
	private final UUID userId = UUID.randomUUID();
	private final UUID planId = UUID.randomUUID();
	private final String key = "adaptive-confirmation-0001";
	private final String hash = "reviewed-payload-hash";
	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final SupportPlanTemplateFamilyRepository families = mock(SupportPlanTemplateFamilyRepository.class);
	private final SupportPlanFocusRepository focuses = mock(SupportPlanFocusRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final SupportPlanActivityOccurrenceService activities = mock(SupportPlanActivityOccurrenceService.class);
	private final SupportPlanScheduleCommandRepository scheduleCommands = mock(SupportPlanScheduleCommandRepository.class);
	private final SupportPlanActivityScheduleRepository schedules = mock(SupportPlanActivityScheduleRepository.class);
	private NativeSupportPlanWriter writer;

	@BeforeEach
	void setup() {
		writer = new NativeSupportPlanWriter(plans, families, focuses, actions, profiles, activities,
				scheduleCommands, mock(SupportPlanScheduleService.class), new ObjectMapper());
		when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(mock(UserProfileEntity.class)));
	}

	@Test
	void preparedDraftReplayComesFromProposalLedger() {
		var request = request(hash);
		when(plans.findRequest(userId, key)).thenReturn(Optional.of(request));

		assertThat(writer.reviewedReplay(userId, key, hash))
				.isEqualTo(new NativeSupportPlanWriter.ReviewedReplay("DRAFT_PREPARED", planId));
		verifyNoInteractions(profiles, activities);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void onlyGuardedReviewedReplacementUsesReviewedActivityActivation(boolean reviewed) {
		var current = current(7);
		var draft = draft();
		when(draft.modelVersion()).thenReturn(3);
		when(draft.planPolicyVersion()).thenReturn(NativeSupportPlanWriter.PROPOSAL_POLICY);
		when(draft.status()).thenReturn("DRAFT");
		when(plans.findByIdAndUserIdForUpdate(draft.id(), userId)).thenReturn(Optional.of(draft));
		var entitlement = new com.mentalbridge.care.entitlement.CurrentEntitlementResponse(userId,
				com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage.PLUS,
				com.mentalbridge.care.entitlement.CurrentEntitlementResponse.EntitlementSource.PAID,
				"paid", NOW.minusSeconds(60), NOW.plusSeconds(60), "service-entitlement-v1", 1, NOW);
		var evidence = new SupportPlanWriter.Revalidation("evaluation-v1", entitlement, "resource-v1", NOW);
		var guard = mock(Runnable.class);
		if (reviewed) {
			writer.replaceReviewed(userId, draft.id(), 0, planId, 7, UUID.randomUUID(),
					"CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE", key, hash, evidence, UUID.randomUUID(), NOW, guard);
			var order = inOrder(guard, current, activities);
			order.verify(guard).run();
			order.verify(current).supersede(NOW);
			order.verify(activities).activateReviewedDraft(draft, NOW);
			verify(activities, never()).activateUserDraft(any(), any());
		}
		else {
			writer.replace(userId, draft.id(), 0, planId, 7, UUID.randomUUID(),
					"CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE", key, hash, evidence, UUID.randomUUID(), NOW);
			verify(activities).activateUserDraft(draft, NOW);
			verify(activities, never()).activateReviewedDraft(any(), any());
			verifyNoInteractions(guard);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"REPLACE", "CYCLE_CONTINUE"})
	void planCommandReplayRetainsItsResultKind(String type) {
		var row = command(type, hash);
		when(plans.findCommand(userId, key)).thenReturn(Optional.of(row));

		assertThat(writer.reviewedReplay(userId, key, hash)).isEqualTo(new NativeSupportPlanWriter.ReviewedReplay(
				"REPLACE".equals(type) ? "REPLACED" : "CYCLE_CONTINUED", planId));
	}

	@Test
	void scheduleReplayRetainsItsResultKind() {
		when(scheduleCommands.findByUserIdAndIdempotencyKey(userId, key)).thenReturn(Optional.of(schedule(hash)));

		assertThat(writer.reviewedReplay(userId, key, hash))
				.isEqualTo(new NativeSupportPlanWriter.ReviewedReplay("SCHEDULE_UPDATED", planId));
	}

	@ParameterizedTest
	@ValueSource(strings = {"REQUEST", "COMMAND", "SCHEDULE"})
	void differentPayloadConflictsInEveryExistingLedger(String ledger) {
		var request = request("other");
		var command = command("REPLACE", "other");
		if ("REQUEST".equals(ledger)) when(plans.findRequest(userId, key)).thenReturn(Optional.of(request));
		if ("COMMAND".equals(ledger)) when(plans.findCommand(userId, key)).thenReturn(Optional.of(command));
		if ("SCHEDULE".equals(ledger)) when(scheduleCommands.findByUserIdAndIdempotencyKey(userId, key))
				.thenReturn(Optional.of(schedule("other")));

		assertConflict(() -> writer.reviewedReplay(userId, key, hash), "IDEMPOTENCY_KEY_REUSED");
	}

	@Test
	void collidingLedgersConflictEvenWithMatchingHashes() {
		var request = request(hash);
		when(plans.findRequest(userId, key)).thenReturn(Optional.of(request));
		when(scheduleCommands.findByUserIdAndIdempotencyKey(userId, key)).thenReturn(Optional.of(schedule(hash)));

		assertConflict(() -> writer.reviewedReplay(userId, key, hash), "IDEMPOTENCY_KEY_REUSED");
	}

	@Test
	void keepChecksVersionUnderOwnerAndPlanLocksBeforeItsGuard() {
		var plan = current(7);
		var guard = mock(Runnable.class);

		assertThat(writer.keepReviewed(userId, planId, 7, guard)).isEqualTo(planId);

		var order = inOrder(profiles, plans, guard);
		order.verify(profiles).findByIdForUpdate(userId);
		order.verify(plans).findByIdAndUserIdForUpdate(planId, userId);
		order.verify(guard).run();
		verify(plans, never()).saveAndFlush(plan);
	}

	@Test
	void staleCurrentCannotPrepareDraftOrRunEvidenceGuard() {
		current(7);
		var guard = mock(Runnable.class);

		assertThatThrownBy(() -> writer.proposeReviewed(userId, planId, 6, key, hash, null, null, null, null,
				"REMINDER", "Reminder", NOW, guard)).isInstanceOfSatisfying(ApiException.class, exception ->
				assertThat(exception.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
		verifyNoInteractions(guard, families, focuses, actions, activities);
		verify(plans, never()).saveAndFlush(any());
	}

	@Test
	void changedLocalEvidenceCannotPrepareAnyDraftState() {
		current(7);

		assertConflict(() -> writer.proposeReviewed(userId, planId, 7, key, hash, null, null, null, null,
				"REMINDER", "Reminder", NOW, () -> { throw stale(); }), "ADAPTIVE_REVIEW_STALE");
		verifyNoInteractions(families, focuses, actions, activities);
		verify(plans, never()).saveAndFlush(any());
	}

	@Test
	void reviewedScheduleUsesReviewedPayloadHashAndGuardsBeforeRecording() {
		var plan = current(7);
		when(plan.modelVersion()).thenReturn(3);
		var action = mock(PlanActionEntity.class);
		var actionId = UUID.randomUUID();
		when(action.supportPlanId()).thenReturn(planId);
		when(action.status()).thenReturn("ACTIVE");
		when(action.actionType()).thenReturn("JOURNAL");
		when(actions.findByIdAndUserIdForUpdate(actionId, userId)).thenReturn(Optional.of(action));
		when(schedules.findBySourceActionIdAndUserIdForUpdate(any(), any())).thenReturn(List.of());
		var guard = mock(Runnable.class);
		var service = scheduleService();

		assertThat(service.updateReviewed(userId, planId, actionId, 7, key,
				new SupportPlanScheduleService.ScheduleCommand(null), hash, guard).replayed()).isFalse();

		var recorded = org.mockito.ArgumentCaptor.forClass(SupportPlanScheduleCommandEntity.class);
		var order = inOrder(profiles, plans, guard, scheduleCommands);
		order.verify(profiles).findByIdForUpdate(userId);
		order.verify(plans).findByIdAndUserIdForUpdate(planId, userId);
		order.verify(guard).run();
		order.verify(scheduleCommands).saveAndFlush(recorded.capture());
		assertThat(recorded.getValue().requestHash()).isEqualTo(hash);
	}

	@Test
	void reviewedScheduleGuardFailureLeavesHistoryAndOccurrencesUntouched() {
		current(7);

		assertConflict(() -> scheduleService().updateReviewed(userId, planId, UUID.randomUUID(), 7, key,
				new SupportPlanScheduleService.ScheduleCommand(null), hash, () -> { throw stale(); }), "ADAPTIVE_REVIEW_STALE");

		verifyNoInteractions(actions, schedules, activities);
		verify(scheduleCommands, never()).saveAndFlush(any());
		verify(plans, never()).saveAndFlush(any());
	}

	@Test
	void reviewedScheduleReplayPrecedesVersionAndEvidenceChecks() {
		when(scheduleCommands.findByUserIdAndIdempotencyKey(userId, key)).thenReturn(Optional.of(schedule(hash)));
		var guard = mock(Runnable.class);

		assertThat(scheduleService().updateReviewed(userId, planId, UUID.randomUUID(), 7, key,
				new SupportPlanScheduleService.ScheduleCommand(null), hash, guard).replayed()).isTrue();

		verify(plans, never()).findByIdAndUserIdForUpdate(any(), any());
		verifyNoInteractions(guard, actions, schedules, activities);
	}

	@Test
	void reviewedResourceAlternativePreservesScheduleAndLeavesNewReviewDueSchedule() {
		var current = current(7);
		var draft = draft();
		var before = resource(current, "resource-before");
		var after = resource(draft, "resource-after");
		var source = scheduleFor(current, before, "ACTIVE", LocalTime.of(20, 35), (short) 18);
		var defaultSchedule = scheduleFor(draft, after, "DRAFT", LocalTime.of(9, 0), (short) 127);
		var reviewDue = mock(SupportPlanActivityScheduleEntity.class);
		when(reviewDue.sourceActionId()).thenReturn(UUID.randomUUID());
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(planId, userId)).thenReturn(List.of(before));
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(draft.id(), userId)).thenReturn(List.of(after));
		when(schedules.findBySupportPlanIdForUpdate(planId)).thenReturn(List.of(source));
		when(schedules.findBySupportPlanIdForUpdate(draft.id())).thenReturn(List.of(defaultSchedule, reviewDue));
		var copied = new ArrayList<SupportPlanActivityScheduleEntity>();
		doAnswer(call -> { copied.addAll(call.getArgument(0)); return copied; }).when(schedules).saveAll(any());

		scheduleService().preserveReviewedDraft(current, draft, NOW);

		verify(schedules).deleteAll(List.of(defaultSchedule));
		assertThat(copied).singleElement().satisfies(value -> {
			assertThat(value.sourceActionId()).isEqualTo(after.id());
			assertThat(value.recurrenceDaysMask()).isEqualTo((short) 18);
			assertThat(value.localTime()).isEqualTo(LocalTime.of(20, 35));
			assertThat(value.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
			assertThat(value.preferredDaypart()).isEqualTo("EVENING");
			assertThat(value.estimatedDurationMinutes()).isEqualTo((short) 15);
			assertThat(value.effectiveFrom()).isEqualTo(LocalDate.of(2026, 10, 10));
			assertThat(value.effectiveUntil()).isEqualTo(LocalDate.of(2026, 11, 1));
			assertThat(value.reminderEnabled()).isTrue();
			assertThat(value.status()).isEqualTo("DRAFT");
		});
		verifyNoInteractions(activities, scheduleCommands);
	}

	@Test
	void reviewedMatchingUnscheduledActionRemainsUnscheduled() {
		var current = current(7);
		var draft = draft();
		var before = resource(current, "same-resource");
		var after = resource(draft, "same-resource");
		var defaultSchedule = scheduleFor(draft, after, "DRAFT", LocalTime.of(9, 0), (short) 127);
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(planId, userId)).thenReturn(List.of(before));
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(draft.id(), userId)).thenReturn(List.of(after));
		when(schedules.findBySupportPlanIdForUpdate(planId)).thenReturn(List.of());
		when(schedules.findBySupportPlanIdForUpdate(draft.id())).thenReturn(List.of(defaultSchedule));

		scheduleService().preserveReviewedDraft(current, draft, NOW);

		verify(schedules).deleteAll(List.of(defaultSchedule));
		verify(schedules).saveAll(List.of());
		verifyNoInteractions(activities, scheduleCommands);
	}

	@Test
	void reviewedScheduleComparisonIgnoresGeneratedIdentityButRejectsDraftTimingEdits() {
		var current = viewWithSchedule("ACTIVE", 3, LocalTime.of(20, 35));
		var draft = viewWithSchedule("DRAFT", 1, LocalTime.of(20, 35));
		var editedDraft = viewWithSchedule("DRAFT", 2, LocalTime.of(9, 0));

		assertThat(scheduleService().reviewedDraftSchedulesMatch(current, draft)).isTrue();
		assertThat(scheduleService().reviewedDraftSchedulesMatch(current, editedDraft)).isFalse();
	}

	private SupportPlanV3Service.SupportPlanV3View viewWithSchedule(String state, int version, LocalTime time) {
		var plan = mock(SupportPlanV3Service.SupportPlanV3View.class);
		var focus = mock(SupportPlanV3Service.PlanFocusView.class);
		var action = mock(SupportPlanV3Service.PlanActionView.class);
		when(plan.focuses()).thenReturn(List.of(focus));
		when(focus.actions()).thenReturn(List.of(action));
		when(action.status()).thenReturn(state);
		when(action.actionType()).thenReturn("RESOURCE");
		when(action.provenance()).thenReturn(new SupportPlanV3Service.ActionProvenanceView(
				"SYSTEM_POLICY", "anxiety-maintenance", SupportPlanService.SELECTION_POLICY_VERSION, NOW));
		when(action.schedules()).thenReturn(List.of(new SupportPlanV3Service.ActionScheduleView(UUID.randomUUID(), version,
				SupportPlanActivityOccurrenceService.SCHEDULE_POLICY_VERSION_V2, "WEEKLY", null, List.of(2, 5),
				"EVENING", time, (short) 15, "Asia/Ho_Chi_Minh", LocalDate.of(2026, 10, 10),
				LocalDate.of(2026, 11, 1), true, state)));
		return plan;
	}

	private SupportPlanEntity draft() {
		var draft = mock(SupportPlanEntity.class);
		when(draft.id()).thenReturn(UUID.randomUUID());
		when(draft.userId()).thenReturn(userId);
		return draft;
	}

	private PlanActionEntity resource(SupportPlanEntity plan, String sourceId) {
		return new PlanActionEntity(UUID.randomUUID(), plan.id(), UUID.randomUUID(), userId, 1,
				"RESOURCE", "SUPPORT", null, new SourceReference("CONTENT", "RESOURCE_VERSION",
						UUID.nameUUIDFromBytes(sourceId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), "1",
						"resource-eligibility-v1", UUID.randomUUID(), "resource-eligibility-v1", NOW),
				new Provenance("SYSTEM_POLICY", "anxiety-maintenance", SupportPlanService.SELECTION_POLICY_VERSION, NOW),
				new Presentation("Support activity", null, null, null), NOW);
	}

	private SupportPlanActivityScheduleEntity scheduleFor(SupportPlanEntity plan, PlanActionEntity action,
			String state, LocalTime time, Short days) {
		return SupportPlanActivityScheduleEntity.revisionForAction(UUID.randomUUID(), plan, action, 1,
				SupportPlanActivityOccurrenceService.SCHEDULE_POLICY_VERSION_V2, "WEEKLY", days, "EVENING", 15,
				time, "Asia/Ho_Chi_Minh", LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 1), true, state, NOW);
	}

	private SupportPlanEntity current(long version) {
		var plan = mock(SupportPlanEntity.class);
		when(plan.id()).thenReturn(planId);
		when(plan.userId()).thenReturn(userId);
		when(plan.version()).thenReturn(version);
		when(plan.status()).thenReturn("ACTIVE");
		when(plans.findByIdAndUserIdForUpdate(planId, userId)).thenReturn(Optional.of(plan));
		return plan;
	}

	private SupportPlanRepository.RequestRow request(String requestHash) {
		var row = mock(SupportPlanRepository.RequestRow.class);
		when(row.getPlanId()).thenReturn(planId);
		when(row.getRequestHash()).thenReturn(requestHash);
		return row;
	}

	private SupportPlanRepository.CommandRow command(String type, String requestHash) {
		var row = mock(SupportPlanRepository.CommandRow.class);
		when(row.getPlanId()).thenReturn(planId);
		when(row.getRequestHash()).thenReturn(requestHash);
		when(row.getCommandType()).thenReturn(type);
		return row;
	}

	private SupportPlanScheduleCommandEntity schedule(String requestHash) {
		return new SupportPlanScheduleCommandEntity(userId, key, requestHash, planId, UUID.randomUUID(),
				7, 8, UUID.randomUUID(), 2, NOW);
	}

	private SupportPlanScheduleService scheduleService() {
		return new SupportPlanScheduleService(plans, mock(SupportPlanSlotRepository.class), actions, schedules,
				scheduleCommands, activities, profiles, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private ApiException stale() {
		return new ApiException(HttpStatus.CONFLICT, "ADAPTIVE_REVIEW_STALE", "Review evidence changed");
	}

	private void assertConflict(Runnable action, String code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
			assertThat(exception.code()).isEqualTo(code);
		});
	}
}
