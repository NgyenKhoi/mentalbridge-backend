package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.reassessment.AdaptiveReviewEvidenceService.OccurrenceEvidence;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ExplicitSelfReport;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.JournalChange;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.JournalCoverage;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.JournalDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ScreeningDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ScreeningPoint;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ScreeningTrend;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.SelfReportedExperienceDimension;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.Change;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.EvidenceRef;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.Pattern;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationSnapshot;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PreferredDaypart;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionPresentationMetadataView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionScheduleView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanActionView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanFocusView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.SupportPlanV3View;

class AdaptiveSupportPlanReviewPolicyTests {

	private static final UUID PLAN_ID = UUID.fromString("64500000-0000-4000-8000-000000000001");
	private static final UUID ACTION_ID = UUID.fromString("64500000-0000-4000-8000-000000000002");
	private static final UUID OTHER_ACTION_ID = UUID.fromString("64500000-0000-4000-8000-000000000003");
	private static final UUID OCCURRENCE_ONE = UUID.fromString("64500000-0000-4000-8000-000000000011");
	private static final UUID OCCURRENCE_TWO = UUID.fromString("64500000-0000-4000-8000-000000000012");
	private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
	private static final String TIMEZONE = "Asia/Ho_Chi_Minh";
	private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 22);
	private final AdaptiveSupportPlanReviewPolicy policy = new AdaptiveSupportPlanReviewPolicy();

	@ParameterizedTest
	@ValueSource(strings = { "NOT_ENOUGH_TIME", "LOW_ENERGY", "NOT_A_GOOD_FIT" })
	void repeatedBarrierUsesDistinctApprovedOccurrencesOfTheSameAction(String barrier) {
		var plan = plan(action(ACTION_ID));
		var first = occurrence(OCCURRENCE_ONE, ACTION_ID, null, barrier);
		var second = occurrence(OCCURRENCE_TWO, ACTION_ID, null, barrier);

		assertThat(policy.patterns(plan, List.of(second, first, first))).singleElement().satisfies(pattern -> {
			assertThat(pattern.kind()).isEqualTo("REPEATED_BARRIER");
			assertThat(pattern.code()).isEqualTo(barrier);
			assertThat(pattern.actionId()).isEqualTo(ACTION_ID);
			assertThat(pattern.actionTitle()).isEqualTo("Action " + ACTION_ID);
			assertThat(pattern.count()).isEqualTo(2);
			assertThat(pattern.occurrenceIds()).containsExactly(OCCURRENCE_ONE, OCCURRENCE_TWO);
		});
		assertThat(policy.patterns(plan, List.of(first, second)))
				.isEqualTo(policy.patterns(plan, List.of(second, first)));
	}

	@Test
	void helpfulReportsRemainSeparateFromARepeatedBarrier() {
		var barrierOne = UUID.fromString("64500000-0000-4000-8000-000000000013");
		var barrierTwo = UUID.fromString("64500000-0000-4000-8000-000000000014");
		var patterns = policy.patterns(plan(action(ACTION_ID)), List.of(
				occurrence(OCCURRENCE_ONE, ACTION_ID, "HELPFUL", null),
				occurrence(OCCURRENCE_TWO, ACTION_ID, "HELPFUL", null),
				occurrence(barrierOne, ACTION_ID, null, "LOW_ENERGY"),
				occurrence(barrierTwo, ACTION_ID, null, "LOW_ENERGY")));

		assertThat(patterns).extracting(Pattern::kind).containsExactlyInAnyOrder("REPEATED_HELPFUL", "REPEATED_BARRIER");
		assertThat(patterns).extracting(Pattern::code).containsExactlyInAnyOrder("HELPFUL", "LOW_ENERGY");
		assertThat(patterns).allSatisfy(pattern -> {
			assertThat(pattern.count()).isEqualTo(2);
			assertThat(pattern.occurrenceIds()).containsExactlyElementsOf("HELPFUL".equals(pattern.code())
					? List.of(OCCURRENCE_ONE, OCCURRENCE_TWO) : List.of(barrierOne, barrierTwo));
		});
	}

	@Test
	void insufficientDifferentActionsAndDifferentCodesDoNotCreateARepeat() {
		assertThat(policy.patterns(plan(action(ACTION_ID), action(OTHER_ACTION_ID)), List.of(
				occurrence(OCCURRENCE_ONE, ACTION_ID, null, "LOW_ENERGY"),
				occurrence(OCCURRENCE_TWO, OTHER_ACTION_ID, null, "LOW_ENERGY"),
				occurrence(UUID.randomUUID(), ACTION_ID, null, "NOT_ENOUGH_TIME")))).isEmpty();
	}

	@Test
	void absentApprovedEvidenceAndUnsupportedCodesDoNotInferAPattern() {
		var plan = plan(action(ACTION_ID));
		assertThat(policy.patterns(plan, List.of())).isEmpty();
		assertThat(policy.patterns(plan, List.of(
				occurrence(OCCURRENCE_ONE, ACTION_ID, null, "OTHER"),
				occurrence(OCCURRENCE_TWO, ACTION_ID, null, "OTHER"),
				occurrence(UUID.randomUUID(), ACTION_ID, "NOT_HELPFUL", null),
				occurrence(UUID.randomUUID(), ACTION_ID, "NOT_HELPFUL", null)))).isEmpty();
	}

	@Test
	void foreignPlanMissingNativeIdentityAndEndedActionsDoNotContribute() {
		var ended = action(OTHER_ACTION_ID);
		when(ended.status()).thenReturn("ENDED");
		var foreign = occurrence(OCCURRENCE_ONE, ACTION_ID, null, "LOW_ENERGY");
		foreign = new OccurrenceEvidence(foreign.occurrenceId(), foreign.occurrenceVersion(), foreign.actionId(),
				foreign.actionVersion(), UUID.randomUUID(), foreign.sourcePlanVersion(), null, null, null,
				foreign.scheduledAt(), foreign.state(), foreign.helpfulness(), foreign.barrierCode(), NOW);

		assertThat(policy.patterns(plan(action(ACTION_ID), ended), List.of(foreign,
				occurrence(OCCURRENCE_TWO, ACTION_ID, null, "LOW_ENERGY"),
				occurrence(OCCURRENCE_ONE, null, null, "LOW_ENERGY"),
				occurrence(OCCURRENCE_TWO, null, null, "LOW_ENERGY"),
				occurrence(OCCURRENCE_ONE, OTHER_ACTION_ID, null, "LOW_ENERGY"),
				occurrence(OCCURRENCE_TWO, OTHER_ACTION_ID, null, "LOW_ENERGY")))).isEmpty();
	}

	@Test
	void feedbackForAnActionOutsideTheCurrentPlanDoesNotCreateARepeat() {
		assertThat(policy.patterns(plan(action(ACTION_ID)), List.of(
				occurrence(OCCURRENCE_ONE, OTHER_ACTION_ID, "HELPFUL", null),
				occurrence(OCCURRENCE_TWO, OTHER_ACTION_ID, "HELPFUL", null)))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "NOT_ENOUGH_TIME", "LOW_ENERGY" })
	void workloadReductionPreservesTheFiveMinuteMinimumAndExactScheduleContext(String barrier) {
		var plan = plan(action(ACTION_ID, schedule("DAILY", List.of(), 10, "MORNING", LocalTime.of(8, 0))));
		var conflict = new EvidenceRef("SCREENING_GAD7", "saved-assessment");
		var candidate = policy.lowerWorkload(plan, List.of(pattern(barrier)), preferences(10, null), List.of(conflict), NOW);

		assertThat(candidate).isNotNull();
		assertThat(candidate.option().kind()).isEqualTo("LOWER_WORKLOAD");
		assertThat(candidate.option().executionKind()).isEqualTo("UPDATE_SCHEDULE");
		assertThat(candidate.option().changes()).containsExactly(new Change("ESTIMATED_DURATION_MINUTES", "10", "5"));
		assertThat(candidate.option().evidenceRefs()).containsExactly(new EvidenceRef("PATTERN", "repeat-" + barrier));
		assertThat(candidate.option().conflictingEvidenceRefs()).containsExactly(conflict);
		var input = candidate.schedule().schedule();
		assertThat(input.estimatedDurationMinutes()).isEqualTo(5);
		assertThat(input.recurrenceType()).isEqualTo("DAILY");
		assertThat(input.localTime()).isEqualTo(LocalTime.of(8, 0));
		assertThat(input.preferredDaypart()).isEqualTo("MORNING");
		assertThat(input.timezone()).isEqualTo(TIMEZONE);
		assertThat(input.effectiveFrom()).isEqualTo(TOMORROW);
		assertThat(input.reminderEnabled()).isTrue();
	}

	@Test
	void minimumDailyDurationCanReduceWeeklyCadenceWithoutDroppingTheAction() {
		var candidate = policy.lowerWorkload(
				plan(action(ACTION_ID, schedule("DAILY", List.of(), 5, "MORNING", LocalTime.of(8, 0)))),
				List.of(pattern("LOW_ENERGY")), preferences(5, null), List.of(), NOW);

		assertThat(candidate).isNotNull();
		assertThat(candidate.option().changes()).containsExactly(new Change("DAYS_PER_WEEK", "7", "3"));
		assertThat(candidate.schedule().schedule().estimatedDurationMinutes()).isEqualTo(5);
		assertThat(candidate.schedule().schedule().recurrenceType()).isEqualTo("WEEKLY");
		assertThat(candidate.schedule().schedule().daysOfWeek()).containsExactly(1, 3, 5);
	}

	@Test
	void weeklyCadenceCanReduceByOneButNeverBelowOneFiveMinuteSession() {
		var candidate = policy.lowerWorkload(
				plan(action(ACTION_ID, schedule("WEEKLY", List.of(1, 3, 5), 5, "MORNING", LocalTime.of(8, 0)))),
				List.of(pattern("NOT_ENOUGH_TIME")), preferences(5, null), List.of(), NOW);

		assertThat(candidate).isNotNull();
		assertThat(candidate.option().changes()).containsExactly(new Change("DAYS_PER_WEEK", "3", "2"));
		assertThat(candidate.schedule().schedule().daysOfWeek()).containsExactly(1, 3);
		assertThat(policy.lowerWorkload(
				plan(action(ACTION_ID, schedule("WEEKLY", List.of(1), 5, "MORNING", LocalTime.of(8, 0)))),
				List.of(pattern("NOT_ENOUGH_TIME")), preferences(5, null), List.of(), NOW)).isNull();
	}

	@Test
	void helpfulOrFitBarrierAloneDoesNotAdmitLowerWorkload() {
		var plan = plan(action(ACTION_ID, schedule("DAILY", List.of(), 10, "MORNING", LocalTime.of(8, 0))));
		assertThat(policy.lowerWorkload(plan, List.of(pattern("HELPFUL"), pattern("NOT_A_GOOD_FIT")),
				preferences(10, null), List.of(), NOW)).isNull();
		assertThat(policy.lowerWorkload(plan, List.of(), preferences(10, null), List.of(), NOW)).isNull();
	}

	@Test
	void absentOrOneTimeScheduleCannotBeReducedIntoInventedRecurrence() {
		assertThat(policy.lowerWorkload(plan(action(ACTION_ID)), List.of(pattern("LOW_ENERGY")),
				preferences(10, null), List.of(), NOW)).isNull();
		assertThat(policy.lowerWorkload(
				plan(action(ACTION_ID, schedule("ONCE", List.of(), 10, "MORNING", LocalTime.of(8, 0)))),
				List.of(pattern("LOW_ENERGY")), preferences(10, null), List.of(), NOW)).isNull();
	}

	@Test
	void workloadCandidateMustFitTheAggregateBudgetOnEveryRetainedDay() {
		var plan = plan(
				action(ACTION_ID, schedule("DAILY", List.of(), 15, "MORNING", LocalTime.of(8, 0))),
				action(OTHER_ACTION_ID, schedule("DAILY", List.of(), 5, "MORNING", LocalTime.of(9, 0))));

		assertThat(policy.lowerWorkload(plan, List.of(pattern("LOW_ENERGY")), preferences(10, null), List.of(), NOW)).isNull();
		assertThat(policy.lowerWorkload(plan, List.of(pattern("LOW_ENERGY")), preferences(15, null), List.of(), NOW))
				.isNotNull();
	}

	@Test
	void futureScheduleBoundaryIsRetainedAndExpiredScheduleIsNotOffered() {
		var future = withDates(schedule("DAILY", List.of(), 10, "MORNING", LocalTime.of(8, 0)),
				TOMORROW.plusDays(3), TOMORROW.plusDays(10));
		var candidate = policy.lowerWorkload(plan(action(ACTION_ID, future)), List.of(pattern("LOW_ENERGY")),
				preferences(10, null), List.of(), NOW);

		assertThat(candidate).isNotNull();
		assertThat(candidate.schedule().schedule().effectiveFrom()).isEqualTo(TOMORROW.plusDays(3));
		assertThat(candidate.schedule().schedule().effectiveUntil()).isEqualTo(TOMORROW.plusDays(10));
		var expired = withDates(future, TOMORROW.minusDays(10), TOMORROW.minusDays(1));
		assertThat(policy.lowerWorkload(plan(action(ACTION_ID, expired)), List.of(pattern("LOW_ENERGY")),
				preferences(10, null), List.of(), NOW)).isNull();
	}

	@Test
	void absentOrFlexibleDaypartNeverInventsATime() {
		var plan = plan(action(ACTION_ID, schedule("DAILY", List.of(), 5, "MORNING", LocalTime.of(8, 0))));

		assertThat(policy.differentTime(plan, null, List.of(), NOW)).isNull();
		assertThat(policy.differentTime(plan, preferences(10, null), List.of(), NOW)).isNull();
		assertThat(policy.differentTime(plan, preferences(10, PreferredDaypart.FLEXIBLE), List.of(), NOW)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "MORNING", "AFTERNOON", "EVENING" })
	void explicitSavedDaypartProducesAnExactSourcedTimeChange(String savedDaypart) {
		var daypart = PreferredDaypart.valueOf(savedDaypart);
		var current = "MORNING".equals(savedDaypart) ? "EVENING" : "MORNING";
		var preferences = preferences(10, daypart);
		var candidate = policy.differentTime(
				plan(action(ACTION_ID, schedule("DAILY", List.of(), 5, current, LocalTime.of(10, 0)))),
				preferences, List.of(), NOW);

		assertThat(candidate).isNotNull();
		assertThat(candidate.option().kind()).isEqualTo("DIFFERENT_TIME");
		assertThat(candidate.option().evidenceRefs())
				.containsExactly(new EvidenceRef("PREFERENCES", preferences.personalizationId().toString()));
		assertThat(candidate.schedule().schedule().preferredDaypart()).isEqualTo(savedDaypart);
		assertThat(candidate.schedule().schedule().localTime()).isEqualTo(switch (daypart) {
			case MORNING -> LocalTime.of(8, 0);
			case AFTERNOON -> LocalTime.of(14, 0);
			case EVENING -> LocalTime.of(19, 0);
			default -> throw new IllegalStateException();
		});
	}

	@Test
	void timeChangeAvoidsOverlapAndAllowsAdjacentSlots() {
		var candidate = policy.differentTime(plan(
				action(ACTION_ID, schedule("DAILY", List.of(), 5, "MORNING", LocalTime.of(8, 0))),
				action(OTHER_ACTION_ID, schedule("DAILY", List.of(), 15, "EVENING", LocalTime.of(19, 0)))),
				preferences(20, PreferredDaypart.EVENING), List.of(), NOW);

		assertThat(candidate).isNotNull();
		assertThat(candidate.option().actionId()).isEqualTo(ACTION_ID);
		assertThat(candidate.schedule().schedule().localTime()).isEqualTo(LocalTime.of(19, 15));
	}

	@Test
	void differentWeekdaysMayUseTheSameTimeWithoutOverlap() {
		var candidate = policy.differentTime(plan(
				action(ACTION_ID, schedule("WEEKLY", List.of(1), 5, "MORNING", LocalTime.of(8, 0))),
				action(OTHER_ACTION_ID, schedule("WEEKLY", List.of(2), 5, "EVENING", LocalTime.of(19, 0)))),
				preferences(5, PreferredDaypart.EVENING), List.of(), NOW);

		assertThat(candidate).isNotNull();
		assertThat(candidate.schedule().schedule().localTime()).isEqualTo(LocalTime.of(19, 0));
		assertThat(candidate.schedule().schedule().daysOfWeek()).containsExactly(1);
	}

	@Test
	void timeChangeDoesNotRelaxTheDailyBudget() {
		assertThat(policy.differentTime(plan(
				action(ACTION_ID, schedule("DAILY", List.of(), 10, "MORNING", LocalTime.of(8, 0))),
				action(OTHER_ACTION_ID, schedule("DAILY", List.of(), 5, "EVENING", LocalTime.of(19, 0)))),
				preferences(10, PreferredDaypart.EVENING), List.of(), NOW)).isNull();
	}

	@Test
	void matchingDaypartAndOneTimeScheduleAreNotChanged() {
		assertThat(policy.differentTime(plan(
				action(ACTION_ID, schedule("DAILY", List.of(), 5, "EVENING", LocalTime.of(19, 30))),
				action(OTHER_ACTION_ID, schedule("ONCE", List.of(), 5, "MORNING", LocalTime.of(8, 0)))),
				preferences(10, PreferredDaypart.EVENING), List.of(), NOW)).isNull();
	}

	@Test
	void opposingStructuredScreeningDirectionsRetainBothExactAssessmentReferences() {
		var summary = summary("DECREASED", "INCREASED");
		assertThat(policy.contradictions(summary)).singleElement().satisfies(contradiction -> {
			assertThat(contradiction.kind()).isEqualTo("INSTRUMENT_DIRECTIONS_DIFFER");
			assertThat(contradiction.evidenceRefs()).containsExactly(
					new EvidenceRef("SCREENING_PHQ9", summary.screening().trends().get(0).current().assessmentId().toString()),
					new EvidenceRef("SCREENING_GAD7", summary.screening().trends().get(1).current().assessmentId().toString()));
		});
	}

	@Test
	void selfReportAndJournalProseAreNeverClassifiedIntoContradictions() {
		assertThat(policy.contradictions(summary("DECREASED", "DECREASED"))).isEmpty();
		assertThat(policy.contradictions(summary("UNCHANGED", "INCREASED"))).isEmpty();
		assertThat(policy.contradictions(summary(null, "INCREASED"))).isEmpty();
		assertThat(policy.contradictions(null)).isEmpty();
	}

	@Test
	void optionIdentityIsStableForTheSameDecisionButChangesWithItsConsequence() {
		var action = action(ACTION_ID, schedule("DAILY", List.of(), 10, "MORNING", LocalTime.of(8, 0)));
		var first = policy.lowerWorkload(plan(action), List.of(pattern("LOW_ENERGY")), preferences(10, null), List.of(), NOW);
		var later = policy.lowerWorkload(plan(action), List.of(pattern("LOW_ENERGY")), preferences(10, null),
				List.of(), NOW.plusSeconds(60));
		var different = policy.option("LOWER_WORKLOAD", "UPDATE_SCHEDULE", ACTION_ID, null,
				first.option().rationaleCodes(), first.option().evidenceRefs(), List.of(),
				List.of(new Change("DAYS_PER_WEEK", "7", "3")));

		assertThat(first.option().optionId()).matches("[a-f0-9]{64}");
		assertThat(later.option().optionId()).isEqualTo(first.option().optionId());
		assertThat(different.optionId()).isNotEqualTo(first.option().optionId());
		assertThat(AdaptiveSupportPlanReviewPolicy.KINDS).containsExactlyInAnyOrder("KEEP_CURRENT", "LOWER_WORKLOAD",
				"DIFFERENT_TIME", "ALTERNATIVE_ACTION", "DIFFERENT_FOCUS", "PROFESSIONAL_SUPPORT_NEXT_STEP");
	}

	private SupportPlanV3View plan(PlanActionView... actions) {
		var plan = mock(SupportPlanV3View.class);
		when(plan.supportPlanId()).thenReturn(PLAN_ID);
		when(plan.focuses()).thenReturn(List.of(new PlanFocusView("focus", "Focus", "Saved goal", null, 0, List.of(actions))));
		return plan;
	}

	private PlanActionView action(UUID id, ActionScheduleView... schedules) {
		var action = mock(PlanActionView.class);
		when(action.actionId()).thenReturn(id.toString());
		when(action.status()).thenReturn("ACTIVE");
		when(action.presentation()).thenReturn(new ActionPresentationMetadataView("Action " + id, null, null, null));
		when(action.schedules()).thenReturn(List.of(schedules));
		return action;
	}

	private ActionScheduleView schedule(String recurrence, List<Integer> days, int minutes, String daypart, LocalTime time) {
		return new ActionScheduleView(UUID.randomUUID(), 2, "support-plan-activity-schedule-v2", recurrence,
				null, days, daypart, time, (short) minutes, TIMEZONE, TOMORROW.minusDays(10), null, true, "ACTIVE");
	}

	private ActionScheduleView withDates(ActionScheduleView schedule, LocalDate from, LocalDate through) {
		return new ActionScheduleView(schedule.scheduleId(), schedule.version(), schedule.schedulePolicyVersion(),
				schedule.recurrenceType(), schedule.recurrenceDayOfWeek(), schedule.daysOfWeek(), schedule.preferredDaypart(),
				schedule.localTime(), schedule.estimatedDurationMinutes(), schedule.timezone(), from, through,
				schedule.reminderEnabled(), schedule.status());
	}

	private PersonalizationSnapshot preferences(Integer budget, PreferredDaypart daypart) {
		return new PersonalizationSnapshot(UUID.fromString("64500000-0000-4000-8000-000000000021"), UUID.randomUUID(),
				"support-plan-personalization-v1", 1, null, null, budget, daypart, List.of(), List.of(), NOW.minusSeconds(60));
	}

	private OccurrenceEvidence occurrence(UUID occurrenceId, UUID actionId, String helpfulness, String barrier) {
		return new OccurrenceEvidence(occurrenceId, 3, actionId, 2L, PLAN_ID, 4, "core", null, null,
				NOW.minusSeconds(86400), barrier == null ? "COMPLETED" : "SKIPPED", helpfulness, barrier, NOW.minusSeconds(3600));
	}

	private Pattern pattern(String code) {
		return new Pattern("repeat-" + code, "HELPFUL".equals(code) ? "REPEATED_HELPFUL" : "REPEATED_BARRIER",
				code, ACTION_ID, "Saved action", 2, List.of(OCCURRENCE_ONE, OCCURRENCE_TWO));
	}

	private ReassessmentSummaryView summary(String phqDirection, String gadDirection) {
		var period = new Period(NOW.minusSeconds(14 * 86400), NOW);
		var report = new ExplicitSelfReport(UUID.randomUUID(), "reassessment-self-report-v1", 2, period,
				"I feel much worse despite lower screening scores", null, null, NOW, NOW);
		var journal = new JournalDimension("AVAILABLE", null, UUID.randomUUID(), UUID.randomUUID(), List.of(),
				List.of("feeling worse"), List.of(), List.of(), List.of(new JournalChange("stress", "MORE_FREQUENT")),
				List.of(), List.of(), List.of(), new JournalCoverage(3, 3, true), null);
		return new ReassessmentSummaryView(UUID.randomUUID(), "reassessment-summary-v2", NOW,
				new Period(NOW.minusSeconds(28 * 86400), period.startAt()), period,
				new ScreeningDimension("AVAILABLE", List.of(trend("PHQ9", phqDirection), trend("GAD7", gadDirection))),
				journal, null, new SelfReportedExperienceDimension("AVAILABLE", null, report), null, null, "NOT_DIAGNOSIS");
	}

	private ScreeningTrend trend(String instrument, String direction) {
		return new ScreeningTrend(instrument, "AVAILABLE", "saved-scoring-v1", null,
				new ScreeningPoint(UUID.randomUUID(), "saved-questionnaire-v1", NOW.minusSeconds(3600), 5, ScreeningLevel.MILD),
				"INCREASED".equals(direction) ? 1 : -1, direction);
	}
}
