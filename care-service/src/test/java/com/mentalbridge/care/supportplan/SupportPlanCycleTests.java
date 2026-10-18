package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.EntitlementSource;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.profile.UserProfileEntity;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;

class SupportPlanCycleTests {

	private final Instant activation = Instant.parse("2026-10-01T16:30:00Z");
	private final UUID userId = UUID.randomUUID();
	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final SupportPlanActivityOccurrenceService activities = mock(SupportPlanActivityOccurrenceService.class);
	private NativeSupportPlanWriter writer;

	@BeforeEach
	void writer() {
		writer = new NativeSupportPlanWriter(plans, mock(SupportPlanTemplateFamilyRepository.class),
				mock(SupportPlanFocusRepository.class), actions, profiles, activities, new ObjectMapper());
		when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(mock(UserProfileEntity.class)));
	}

	@Test
	void draftHasNoStartAndOlderPoliciesHaveNoCycle() {
		var plan = plan(NativeSupportPlanWriter.PROPOSAL_POLICY);
		var cycle = SupportPlanCycle.view(plan, activation);
		assertThat(cycle.state()).isEqualTo("NOT_STARTED");
		assertThat(cycle.cycleNumber()).isEqualTo(1);
		assertThat(cycle.startedOn()).isNull();
		assertThat(cycle.reviewDueOn()).isNull();
		assertThat(cycle.currentDay()).isNull();
		assertThat(SupportPlanCycle.view(plan(NativeSupportPlanWriter.POLICY), activation)).isNull();
		assertThat(SupportPlanCycle.view(plan(NativeSupportPlanWriter.EMOTION_POLICY), activation)).isNull();
	}

	@Test
	void activationUsesLocalDateAndReviewDueDoesNotChangeLifecycle() {
		var plan = activated("Asia/Ho_Chi_Minh");
		var beforeMidnight = SupportPlanCycle.view(plan, activation);
		var afterMidnight = SupportPlanCycle.view(plan, Instant.parse("2026-10-01T17:01:00Z"));
		assertThat(beforeMidnight.startedOn()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(beforeMidnight.currentDay()).isEqualTo(1);
		assertThat(afterMidnight.currentDay()).isEqualTo(2);
		assertThat(afterMidnight.reviewDueOn()).isEqualTo(LocalDate.of(2026, 10, 14));
		assertThat(SupportPlanCycle.view(plan, Instant.parse("2026-10-13T17:00:00Z")).state()).isEqualTo("REVIEW_DUE");
		assertThat(plan.status()).isEqualTo("ACTIVE");
		assertThat(plan.completedAt()).isNull();
		assertThat(plan.cycleNumber()).isEqualTo(1);
	}

	@Test
	void multiplePausesAndOngoingPauseCountWholeLocalDates() {
		var plan = activated("Asia/Ho_Chi_Minh");
		plan.pause(Instant.parse("2026-10-02T03:00:00Z"));
		assertThat(SupportPlanCycle.view(plan, Instant.parse("2026-10-02T12:00:00Z")).pausedDays()).isZero();
		var ongoing = SupportPlanCycle.view(plan, Instant.parse("2026-10-04T03:00:00Z"));
		assertThat(ongoing.pausedDays()).isEqualTo(2);
		assertThat(ongoing.currentDay()).isEqualTo(2);
		assertThat(ongoing.reviewDueOn()).isEqualTo(LocalDate.of(2026, 10, 16));
		assertThat(ongoing.state()).isEqualTo("PAUSED");
		plan.resume(Instant.parse("2026-10-04T03:00:00Z"));
		plan.pause(Instant.parse("2026-10-06T03:00:00Z"));
		plan.resume(Instant.parse("2026-10-09T03:00:00Z"));
		var result = SupportPlanCycle.view(plan, Instant.parse("2026-10-09T03:00:00Z"));
		assertThat(result.pausedDays()).isEqualTo(5);
		assertThat(result.reviewDueOn()).isEqualTo(LocalDate.of(2026, 10, 19));
		assertThat(result.currentDay()).isEqualTo(4);
		assertThat(plan.cyclePausedOn()).isNull();
	}

	@Test
	void dstPauseCountsCalendarDaysAndTerminalReadFreezesPause() {
		var plan = plan(NativeSupportPlanWriter.PROPOSAL_POLICY);
		plan.initializeCycle("America/New_York");
		plan.activate(Instant.parse("2026-10-30T12:00:00Z"));
		plan.pause(Instant.parse("2026-10-31T16:00:00Z"));
		plan.complete("USER_DECISION", Instant.parse("2026-11-02T17:00:00Z"));
		var result = SupportPlanCycle.view(plan, Instant.parse("2026-12-31T12:00:00Z"));
		assertThat(result.pausedDays()).isEqualTo(2);
		assertThat(result.state()).isEqualTo("CLOSED");
		assertThat(result.currentDay()).isEqualTo(2);
		assertThat(plan.cyclePausedOn()).isNull();
	}

	@Test
	void continuationStartsNextCycle() {
		var plan = activated("Asia/Ho_Chi_Minh");
		when(plans.findByIdAndUserIdForUpdate(plan.id(), userId)).thenReturn(Optional.of(plan));
		when(plans.saveAndFlush(plan)).thenReturn(plan);
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(plan.id(), userId))
				.thenReturn(List.of(resource(plan)));
		Instant due = Instant.parse("2026-10-14T08:00:00Z");
		assertThat(writer.continueCycle(userId, plan.id(), 0, "cycle-continue-key-0001", "hash", entitlement(), due)).isEqualTo(plan.id());
		var cycle = SupportPlanCycle.view(plan, due);
		assertThat(cycle.cycleNumber()).isEqualTo(2);
		assertThat(cycle.startedOn()).isEqualTo(LocalDate.of(2026, 10, 14));
		assertThat(cycle.reviewDueOn()).isEqualTo(LocalDate.of(2026, 10, 27));
		assertThat(cycle.currentDay()).isEqualTo(1);
		assertThat(cycle.pausedDays()).isZero();
		assertThat(cycle.state()).isEqualTo("IN_PROGRESS");
		assertThat(plan.activatedAt()).isEqualTo(activation);
		verify(activities).continueCycle(plan, due);
	}

	@Test
	void continuationRejectedBeforeReviewDue() {
		var plan = activated("Asia/Ho_Chi_Minh");
		when(plans.findByIdAndUserIdForUpdate(plan.id(), userId)).thenReturn(Optional.of(plan));
		assertThatThrownBy(() -> writer.continueCycle(userId, plan.id(), 0, "cycle-continue-key-0001", "hash", entitlement(), activation))
				.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("SUPPORT_PLAN_CYCLE_NOT_REVIEW_DUE"));
		verify(activities, never()).continueCycle(any(), any());
	}

	@Test
	void continuationIdempotentReplay() {
		var plan = activated("Asia/Ho_Chi_Minh");
		plan.continueCycle(Instant.parse("2026-10-14T08:00:00Z"));
		var command = mock(SupportPlanRepository.CommandRow.class);
		when(command.getPlanId()).thenReturn(plan.id());
		when(command.getRequestHash()).thenReturn("hash");
		when(command.getCommandType()).thenReturn("CYCLE_CONTINUE");
		when(plans.findCommand(userId, "cycle-continue-key-0001")).thenReturn(Optional.of(command));
		when(plans.findByIdAndUserId(plan.id(), userId)).thenReturn(Optional.of(plan));
		assertThat(writer.continueCycle(userId, plan.id(), 0, "cycle-continue-key-0001", "hash", entitlement(), activation)).isEqualTo(plan.id());
		assertThat(plan.cycleNumber()).isEqualTo(2);
		verify(plans, never()).findByIdAndUserIdForUpdate(any(), any());
		verify(activities, never()).continueCycle(any(), any());
	}

	@Test
	void continuationVersionMismatch412() {
		var plan = activated("Asia/Ho_Chi_Minh");
		when(plans.findByIdAndUserIdForUpdate(plan.id(), userId)).thenReturn(Optional.of(plan));
		assertThatThrownBy(() -> writer.continueCycle(userId, plan.id(), 1, "cycle-continue-key-0001", "hash", entitlement(), activation))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.status().value()).isEqualTo(412);
					assertThat(error.code()).isEqualTo("SUPPORT_PLAN_VERSION_MISMATCH");
				});
	}

	@Test
	void pauseStartingAfterReviewDueNeverMovesTheReviewDate() {
		var plan = activated("Asia/Ho_Chi_Minh");
		// Due 10-14; the user pauses on day 20 and resumes on day 25.
		plan.pause(Instant.parse("2026-10-20T03:00:00Z"));
		var paused = SupportPlanCycle.view(plan, Instant.parse("2026-10-24T03:00:00Z"));
		assertThat(paused.pausedDays()).isZero();
		assertThat(paused.reviewDueOn()).isEqualTo(LocalDate.of(2026, 10, 14));
		plan.resume(Instant.parse("2026-10-25T03:00:00Z"));
		var resumed = SupportPlanCycle.view(plan, Instant.parse("2026-10-25T03:00:00Z"));
		assertThat(resumed.pausedDays()).isZero();
		assertThat(resumed.reviewDueOn()).isEqualTo(LocalDate.of(2026, 10, 14));
		assertThat(resumed.state()).isEqualTo("REVIEW_DUE");
	}

	@Test
	void pauseStartingBeforeReviewDueStillCountsEveryPausedDay() {
		var plan = activated("Asia/Ho_Chi_Minh");
		plan.pause(Instant.parse("2026-10-13T03:00:00Z"));
		plan.resume(Instant.parse("2026-10-17T03:00:00Z"));
		var result = SupportPlanCycle.view(plan, Instant.parse("2026-10-17T03:00:00Z"));
		assertThat(result.pausedDays()).isEqualTo(4);
		assertThat(result.reviewDueOn()).isEqualTo(LocalDate.of(2026, 10, 18));
	}

	private CurrentEntitlementResponse entitlement() {
		return new CurrentEntitlementResponse(userId, ServicePackage.PLUS, EntitlementSource.DEMO, "cycle-unit",
				activation.minusSeconds(86400), activation.plusSeconds(86400 * 60L), "service-entitlement-v1", 2, activation);
	}

	private SupportPlanEntity activated(String timezone) {
		var plan = plan(NativeSupportPlanWriter.PROPOSAL_POLICY);
		plan.initializeCycle(timezone);
		plan.activate(activation);
		return plan;
	}

	private SupportPlanEntity plan(String policy) {
		return SupportPlanEntity.nativeDraft(UUID.randomUUID(), userId, UUID.randomUUID(), "mb-support-routing-capstone-v2",
				activation, policy, "PLUS", "DEMO", "service-entitlement-v1", 1, activation,
				"DOMAIN_AWARE_WELLBEING_SUPPORT", "Plan rationale", "NEGATIVE_SAFETY_SCREEN", "NO_TRIGGER", "safety-v1",
				"SAFETY", "Safety guidance", 3, activation);
	}

	private PlanActionEntity resource(SupportPlanEntity plan) {
		return new PlanActionEntity(UUID.randomUUID(), plan.id(), UUID.randomUUID(), userId, 1,
				"RESOURCE", "SUPPORT", null, new SourceReference("CONTENT", "RESOURCE_VERSION", UUID.randomUUID().toString(),
						"1", "content-eligibility-v1", UUID.randomUUID(), "content-eligibility-v1", activation),
				new Provenance("SYSTEM_POLICY", "resource-slot", "policy", activation),
				new PlanActionEntity.Presentation("Reviewed resource", null, null, null), activation);
	}
}
