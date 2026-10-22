package com.mentalbridge.care.guidedjourney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mentalbridge.care.guidedjourney.GuidedJourneyService.EntitlementStatus;
import com.mentalbridge.care.guidedjourney.GuidedJourneyService.JourneyView;
import com.mentalbridge.care.guidedjourney.GuidedJourneyService.NextAction;
import com.mentalbridge.care.guidedjourney.GuidedJourneyService.Stage;
import com.mentalbridge.care.guidedjourney.HomeNextStepService.Focus;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanCycle;
import com.mentalbridge.care.supportplan.SupportPlanV3Service;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionOccurrenceView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionPresentationMetadataView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionScheduleView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.CurrentDayView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanActionView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanFocusView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanHistoryStatusView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanStatusView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.SupportPlanV3View;

class HomeNextStepServiceTests {

	private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000672");
	private static final UUID PLAN_ID = UUID.fromString("20000000-0000-4000-8000-000000000672");
	private static final UUID ACTION_ID = UUID.fromString("30000000-0000-4000-8000-000000000672");
	private static final UUID OTHER_ACTION_ID = UUID.fromString("40000000-0000-4000-8000-000000000672");
	private static final UUID SCHEDULE_ID = UUID.fromString("50000000-0000-4000-8000-000000000672");
	private static final UUID CORRELATION_ID = UUID.fromString("60000000-0000-4000-8000-000000000672");
	private static final Instant NOW = Instant.parse("2026-10-22T17:30:00Z");
	private static final LocalDate TODAY = LocalDate.parse("2026-10-23");
	private static final String PLAN_TIMEZONE = "Asia/Ho_Chi_Minh";
	private final GuidedJourneyService journeys = mock(GuidedJourneyService.class);
	private final SupportPlanV3Service plans = mock(SupportPlanV3Service.class);
	private final HomeNextStepService service = new HomeNextStepService(journeys, plans);
	private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
	private JourneyView journey;

	@BeforeEach
	void initialJourney() {
		journey = new JourneyView("mb-guided-journey-v1", Stage.GUIDE_READY, false, UUID.randomUUID(),
				NOW.minusSeconds(3600), UUID.randomUUID(), UUID.randomUUID(), NextAction.OPEN_CURRENT_PLAN,
				PLAN_ID, EntitlementStatus.NOT_REQUIRED);
		when(journeys.current(USER_ID, "token", CORRELATION_ID)).thenReturn(journey);
	}

	@Test
	void embedsTheExactExistingJourneyAndPreservesFirstUseState() {
		journey = new JourneyView("mb-guided-journey-v1", Stage.ASSESSMENT_REQUIRED, true, null,
				null, null, null, null, null, EntitlementStatus.NOT_REQUIRED);
		when(journeys.current(USER_ID, "token", CORRELATION_ID)).thenReturn(journey);

		var home = service.current(USER_ID, "token", CORRELATION_ID, null);

		assertThat(home.homePolicyVersion()).isEqualTo("mb-home-next-step-v1");
		assertThat(home.journey()).isSameAs(journey);
		assertThat(mapper.valueToTree(home).get("journey")).isEqualTo(mapper.valueToTree(journey));
		assertThat(home.plan()).isNull();
		assertThat(home.lastCompletedPlan()).isNull();
		verify(journeys).current(USER_ID, "token", CORRELATION_ID);
	}

	@Test
	void preservesEntitlementOutageResourcesWithoutReinterpretingTheJourney() {
		journey = new JourneyView("mb-guided-journey-v1", Stage.GUIDE_READY, false, UUID.randomUUID(),
				NOW.minusSeconds(3600), UUID.randomUUID(), UUID.randomUUID(), NextAction.EXPLORE_GUIDE_RESOURCES,
				null, EntitlementStatus.UNAVAILABLE);
		when(journeys.current(USER_ID, "token", CORRELATION_ID)).thenReturn(journey);

		assertThat(service.current(USER_ID, "token", CORRELATION_ID, null).journey()).isSameAs(journey);
	}

	@Test
	void surfacesTheJourneyPolicyFailure() {
		when(journeys.current(USER_ID, "token", CORRELATION_ID)).thenThrow(new ApiException(
				HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_POLICY_UNAVAILABLE", "No published support policy"));

		assertThatThrownBy(() -> service.current(USER_ID, "token", CORRELATION_ID, null))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(error.code()).isEqualTo("SUPPORT_POLICY_UNAVAILABLE");
				});
	}

	@Test
	void choosesTodayAndCopiesOnlyTheSelectedActionPresentation() {
		var occurrence = occurrence(ACTION_ID, "SCHEDULED", NOW.plusSeconds(3600));
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1))), List.of(occurrence));

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();

		assertThat(plan.supportPlanId()).isEqualTo(PLAN_ID);
		assertThat(plan.status()).isEqualTo("ACTIVE");
		assertThat(plan.focus()).isEqualTo(Focus.DO_TODAY);
		assertThat(plan.today().total()).isEqualTo(1);
		assertThat(plan.today().completed()).isZero();
		assertThat(plan.nextOccurrence().occurrenceId()).isEqualTo(occurrence.occurrenceId());
		assertThat(plan.nextOccurrence().actionId().toString()).isEqualTo(ACTION_ID.toString());
		assertThat(plan.nextOccurrence().actionType()).isEqualTo("RESOURCE");
		assertThat(plan.nextOccurrence().title()).isEqualTo("A small next step");
		assertThat(plan.nextOccurrence().displayState()).isEqualTo("SCHEDULED");
		assertThat(plan.nextOccurrence().scheduledAt()).isEqualTo(occurrence.scheduledAt());
		assertThat(mapper.valueToTree(plan.nextOccurrence()).get("estimatedDurationMinutes").asInt()).isEqualTo(5);
	}

	@Test
	void copiesTheCurrentDayAndLengthFromThePlanCycle() {
		var cycle = new SupportPlanCycle.View("support-plan-cycle-v1", 21, PLAN_TIMEZONE, 1,
				TODAY.minusDays(5), TODAY.plusDays(15), 6, 0, "IN_PROGRESS");
		currentPlan("ACTIVE", List.of(focus(1, action(ACTION_ID, 1))), List.of(), cycle);

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();

		assertThat(plan.cycleDay()).isEqualTo(6);
		assertThat(plan.cycleLengthDays()).isEqualTo(21);
	}

	@Test
	void aPlanWithoutACycleIncludesBothCycleFieldsAsExplicitNulls() {
		currentPlan("ACTIVE", List.of(focus(1, action(ACTION_ID, 1))), List.of(), null);

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();
		var json = mapper.valueToTree(plan);

		assertThat(plan.cycleDay()).isNull();
		assertThat(plan.cycleLengthDays()).isNull();
		assertThat(json.has("cycleDay")).isTrue();
		assertThat(json.get("cycleDay").isNull()).isTrue();
		assertThat(json.has("cycleLengthDays")).isTrue();
		assertThat(json.get("cycleLengthDays").isNull()).isTrue();
	}

	@Test
	void pausedPlanPrecedesReviewDueAndOpenOccurrences() {
		currentPlan("PAUSED", "REVIEW_DUE", List.of(focus(1, action(ACTION_ID, 1))),
				List.of(occurrence(ACTION_ID, "OVERDUE", NOW.minusSeconds(60))));

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();

		assertThat(plan.focus()).isEqualTo(Focus.RESUME_PAUSED);
		assertThat(plan.status()).isEqualTo("PAUSED");
		assertThat(plan.reviewDueOn()).isEqualTo(TODAY);
		assertThat(plan.nextOccurrence()).isNull();
	}

	@Test
	void reviewDuePrecedesOpenOccurrencesWithoutChangingThePlanState() {
		currentPlan("ACTIVE", "REVIEW_DUE", List.of(focus(1, action(ACTION_ID, 1))),
				List.of(occurrence(ACTION_ID, "OVERDUE", NOW.minusSeconds(60))));

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();

		assertThat(plan.focus()).isEqualTo(Focus.REVIEW_DUE);
		assertThat(plan.status()).isEqualTo("ACTIVE");
		assertThat(plan.reviewDueOn()).isEqualTo(TODAY);
		assertThat(plan.nextOccurrence()).isNull();
	}

	@Test
	void completedAndSkippedOccurrencesCloseTodayButOnlyCompletedCountsAsCompleted() {
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1))), List.of(
				occurrence(ACTION_ID, "COMPLETED", NOW.minusSeconds(120)),
				occurrence(ACTION_ID, "SKIPPED", NOW.minusSeconds(60))));

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();

		assertThat(plan.focus()).isEqualTo(Focus.TODAY_DONE);
		assertThat(plan.today().total()).isEqualTo(2);
		assertThat(plan.today().completed()).isEqualTo(1);
		assertThat(plan.nextOccurrence()).isNull();
	}

	@Test
	void noOccurrencesIsANeutralNothingToday() {
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1))), List.of());

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();

		assertThat(plan.focus()).isEqualTo(Focus.NOTHING_TODAY);
		assertThat(plan.today().total()).isZero();
		assertThat(plan.today().completed()).isZero();
		assertThat(plan.nextOccurrence()).isNull();
	}

	@Test
	void cancelledOccurrencesDoNotCreateWorkOrInflateTheTodayCounts() {
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1))), List.of(
				occurrence(ACTION_ID, "CANCELLED", NOW.minusSeconds(120)),
				occurrence(ACTION_ID, "COMPLETED", NOW.minusSeconds(60))));

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();

		assertThat(plan.focus()).isEqualTo(Focus.TODAY_DONE);
		assertThat(plan.today().total()).isEqualTo(1);
		assertThat(plan.today().completed()).isEqualTo(1);
	}

	@Test
	void aDayContainingOnlyCancelledOccurrencesIsNothingToday() {
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1))),
				List.of(occurrence(ACTION_ID, "CANCELLED", NOW.minusSeconds(120))));

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();

		assertThat(plan.focus()).isEqualTo(Focus.NOTHING_TODAY);
		assertThat(plan.today().total()).isZero();
	}

	@Test
	void earliestScheduledTimePrecedesActionOrderAndIncludesOverdueWork() {
		var later = occurrence(ACTION_ID, "SCHEDULED", NOW.plusSeconds(3600));
		var earlier = occurrence(OTHER_ACTION_ID, "OVERDUE", NOW.minusSeconds(60));
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1),
				action(OTHER_ACTION_ID, 2))), List.of(later, earlier));

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();

		assertThat(plan.focus()).isEqualTo(Focus.DO_TODAY);
		assertThat(plan.nextOccurrence().occurrenceId()).isEqualTo(earlier.occurrenceId());
		assertThat(plan.nextOccurrence().displayState()).isEqualTo("OVERDUE");
	}

	@Test
	void equalScheduledTimesUseFocusOrdinalBeforeActionOrdinalOrInputOrder() {
		var secondFocus = occurrence(ACTION_ID, "SCHEDULED", NOW.plusSeconds(60));
		var firstFocus = occurrence(OTHER_ACTION_ID, "SCHEDULED", NOW.plusSeconds(60));
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(2, action(ACTION_ID, 1)),
				focus(1, action(OTHER_ACTION_ID, 3))), List.of(secondFocus, firstFocus));

		assertThat(service.current(USER_ID, "token", CORRELATION_ID, null).plan().nextOccurrence().occurrenceId())
				.isEqualTo(firstFocus.occurrenceId());
	}

	@Test
	void equalScheduledTimesWithinAFocusUseActionOrdinalInsteadOfInputOrder() {
		var second = occurrence(ACTION_ID, "SCHEDULED", NOW.plusSeconds(60));
		var first = occurrence(OTHER_ACTION_ID, "SCHEDULED", NOW.plusSeconds(60));
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 2),
				action(OTHER_ACTION_ID, 1))), List.of(second, first));

		assertThat(service.current(USER_ID, "token", CORRELATION_ID, null).plan().nextOccurrence().occurrenceId())
				.isEqualTo(first.occurrenceId());
	}

	@Test
	void datedOccurrencesPrecedeOccurrencesWithoutAScheduledTime() {
		var undated = occurrence(ACTION_ID, "SCHEDULED", null);
		var dated = occurrence(OTHER_ACTION_ID, "SCHEDULED", NOW.plusSeconds(60));
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1),
				action(OTHER_ACTION_ID, 2))), List.of(undated, dated));

		assertThat(service.current(USER_ID, "token", CORRELATION_ID, null).plan().nextOccurrence().occurrenceId())
				.isEqualTo(dated.occurrenceId());
	}

	@Test
	void omittedTimezoneUsesTheAuthoritativePlanLocalDateAndTimezone() {
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1))), List.of());

		var plan = service.current(USER_ID, "token", CORRELATION_ID, null).plan();

		assertThat(plan.localDate()).isEqualTo(TODAY);
		assertThat(plan.timeZone()).isEqualTo(PLAN_TIMEZONE);
		verify(plans).currentDay(USER_ID, null);
	}

	@Test
	void aValidBrowserTimezoneDeterminesTodayInsteadOfThePlanScheduleZone() {
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1))), List.of());
		var browserToday = LocalDate.parse("2026-10-22");

		var plan = service.current(USER_ID, "token", CORRELATION_ID, "America/Los_Angeles").plan();

		assertThat(plan.localDate()).isEqualTo(browserToday);
		assertThat(plan.timeZone()).isEqualTo("America/Los_Angeles");
		verify(plans).currentDay(USER_ID, ZoneId.of("America/Los_Angeles"));
	}

	@Test
	void invalidTimezoneIsBadRequestEvenWithoutACurrentPlan() {
		for (String invalid : List.of("Mars/Olympus", "+07:00", "", " ")) {
			assertThatThrownBy(() -> service.current(USER_ID, "token", CORRELATION_ID, invalid))
					.isInstanceOfSatisfying(ApiException.class,
							error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
		}
	}

	@Test
	void utcIsAValidIanaTimezone() {
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1))), List.of());

		var plan = service.current(USER_ID, "token", CORRELATION_ID, "UTC").plan();

		assertThat(plan.timeZone()).isEqualTo("UTC");
		assertThat(plan.localDate()).isEqualTo(LocalDate.parse("2026-10-22"));
	}

	@Test
	void aDraftRemainsOnlyInTheJourneyAndSuppressesCompletedPlanHistory() {
		journey = new JourneyView("mb-guided-journey-v1", Stage.GUIDE_READY, false, UUID.randomUUID(), NOW,
				UUID.randomUUID(), UUID.randomUUID(), NextAction.REVIEW_PLAN_DRAFT, PLAN_ID,
				EntitlementStatus.NOT_REQUIRED);
		when(journeys.current(USER_ID, "token", CORRELATION_ID)).thenReturn(journey);
		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.of(new PlanStatusView(PLAN_ID, "DRAFT")));
		when(plans.latestHistoryStatus(USER_ID))
				.thenReturn(Optional.of(new PlanHistoryStatusView(UUID.randomUUID(), "COMPLETED", NOW)));

		var home = service.current(USER_ID, "token", CORRELATION_ID, null);

		assertThat(home.plan()).isNull();
		assertThat(home.journey()).isSameAs(journey);
		assertThat(home.lastCompletedPlan()).isNull();
		verify(plans, never()).current(USER_ID);
		verify(plans, never()).occurrences(any(), any(), any());
	}

	@Test
	void aCurrentPlanSuppressesCompletedPlanHistory() {
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1))), List.of());
		when(plans.latestHistoryStatus(USER_ID))
				.thenReturn(Optional.of(new PlanHistoryStatusView(UUID.randomUUID(), "COMPLETED", NOW)));

		assertThat(service.current(USER_ID, "token", CORRELATION_ID, null).lastCompletedPlan()).isNull();
	}

	@Test
	void exposesTheNewestCompletedPlanOnlyWhenNoCurrentOrDraftExists() {
		var completedPlanId = UUID.randomUUID();
		var completedAt = NOW.minusSeconds(86400);
		when(plans.latestHistoryStatus(USER_ID))
				.thenReturn(Optional.of(new PlanHistoryStatusView(completedPlanId, "COMPLETED", completedAt)));

		var home = service.current(USER_ID, "token", CORRELATION_ID, null);

		assertThat(home.plan()).isNull();
		assertThat(home.lastCompletedPlan().supportPlanId()).isEqualTo(completedPlanId);
		assertThat(home.lastCompletedPlan().completedAt()).isEqualTo(completedAt);
	}

	@Test
	void aNewestDiscardedOrSupersededPlanDoesNotExposeAnOlderCompletedPlan() {
		for (String status : List.of("DISCARDED", "SUPERSEDED")) {
			when(plans.latestHistoryStatus(USER_ID))
					.thenReturn(Optional.of(new PlanHistoryStatusView(UUID.randomUUID(), status, null)));

			assertThat(service.current(USER_ID, "token", CORRELATION_ID, null).lastCompletedPlan()).isNull();
		}
	}

	@Test
	void durationIsUnknownWhenTheOccurrenceReferencesAnOlderScheduleVersion() {
		var action = action(ACTION_ID, 1, schedule(2));
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action)),
				List.of(occurrence(ACTION_ID, "SCHEDULED", NOW.plusSeconds(60))));

		assertThat(service.current(USER_ID, "token", CORRELATION_ID, null).plan().nextOccurrence()
				.estimatedDurationMinutes()).isNull();
	}

	@Test
	void serializedHomeContainsOnlyTheMinimizedDecisionFields() throws Exception {
		var occurrence = occurrence(ACTION_ID, "SCHEDULED", NOW.plusSeconds(60));
		currentPlan("ACTIVE", "IN_PROGRESS", List.of(focus(1, action(ACTION_ID, 1))), List.of(occurrence));

		var json = mapper.readTree(mapper.writeValueAsString(service.current(USER_ID, "token", CORRELATION_ID, null)));

		assertThat(json.properties()).extracting(java.util.Map.Entry::getKey)
				.containsExactlyInAnyOrder("homePolicyVersion", "journey", "plan", "lastCompletedPlan");
		assertThat(json.get("plan").properties()).extracting(java.util.Map.Entry::getKey)
				.containsExactlyInAnyOrder("supportPlanId", "status", "focus", "reviewDueOn", "cycleDay",
						"cycleLengthDays", "localDate", "timeZone", "today", "nextOccurrence");
		assertThat(json.get("plan").get("nextOccurrence").properties()).extracting(java.util.Map.Entry::getKey)
				.containsExactlyInAnyOrder("occurrenceId", "actionId", "actionType", "title", "displayState",
						"scheduledAt", "estimatedDurationMinutes");
		assertThat(json.toString()).doesNotContain("private reflection", "private action summary", "answers",
				"score", "completionEvidence", "reflection", "summaryReuseApproved", "engagementUpdatedAt");
	}

	private void currentPlan(String status, String cycleState, List<PlanFocusView> focuses,
			List<ActionOccurrenceView> occurrences) {
		var cycle = new SupportPlanCycle.View("support-plan-cycle-v1", 14, PLAN_TIMEZONE, 1,
				TODAY.minusDays(13), TODAY, 14, 0, cycleState);
		currentPlan(status, focuses, occurrences, cycle);
	}

	private void currentPlan(String status, List<PlanFocusView> focuses, List<ActionOccurrenceView> occurrences,
			SupportPlanCycle.View cycle) {
		var plan = new SupportPlanV3View(PLAN_ID, 3, "support-plan-v3-actions-v1", "NATIVE_V3", status,
				1, null, focuses, NOW.minusSeconds(86400), NOW, NOW.minusSeconds(86400), null, null,
				null, null, "WELLBEING_SUPPORT_NOT_TREATMENT", "Support only", cycle);
		when(plans.currentDay(eq(USER_ID), any())).thenAnswer(invocation -> {
			ZoneId requestedZone = invocation.getArgument(1);
			ZoneId zone = requestedZone == null ? ZoneId.of(PLAN_TIMEZONE) : requestedZone;
			return Optional.of(new CurrentDayView(plan, LocalDate.ofInstant(NOW, zone), zone.getId(), occurrences));
		});
	}

	private PlanFocusView focus(int ordinal, PlanActionView... actions) {
		return new PlanFocusView(UUID.randomUUID().toString(), "Daily support", "Private plan rationale", null,
				ordinal, List.of(actions));
	}

	private PlanActionView action(UUID id, int ordinal, ActionScheduleView... schedules) {
		var actionSchedules = schedules.length == 0 ? List.of(schedule(1)) : List.of(schedules);
		return new PlanActionView(id.toString(), "RESOURCE", 1, "NATIVE_V3", "ACTIVE", "PRIMARY", "READ_CONTENT",
				"Private action rationale", ordinal, null, null,
				new ActionPresentationMetadataView("A small next step", "private action summary", "Private instructions",
						"https://example.test/private"), actionSchedules, NOW, NOW, NOW, null);
	}

	private ActionScheduleView schedule(int version) {
		return new ActionScheduleView(SCHEDULE_ID, version, "support-plan-activity-schedule-v2", "DAILY", null,
				List.of(), null, LocalTime.of(8, 0), (short) 5, PLAN_TIMEZONE, TODAY.minusDays(1), null, false, "ACTIVE");
	}

	private ActionOccurrenceView occurrence(UUID actionId, String displayState, Instant scheduledAt) {
		String state = "OVERDUE".equals(displayState) ? "SCHEDULED" : displayState;
		return new ActionOccurrenceView(UUID.randomUUID(), actionId.toString(), "RESOURCE", 1, SCHEDULE_ID, 1,
				"NATIVE_V3", 0, TODAY, scheduledAt, state, displayState, null, null, null, false, "HELPFUL", null,
				"private reflection", false, NOW);
	}
}
