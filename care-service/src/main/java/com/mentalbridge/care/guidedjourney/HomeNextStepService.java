package com.mentalbridge.care.guidedjourney;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.guidedjourney.GuidedJourneyService.JourneyView;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanV3Service;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionOccurrenceView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanActionView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanFocusView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.SupportPlanV3View;

@Service
public class HomeNextStepService {

	private static final String POLICY_VERSION = "mb-home-next-step-v1";
	private final GuidedJourneyService journeys;
	private final SupportPlanV3Service plans;

	public HomeNextStepService(GuidedJourneyService journeys, SupportPlanV3Service plans) {
		this.journeys = journeys;
		this.plans = plans;
	}

	public HomeNextStepView current(UUID userId, String bearerToken, UUID correlationId, String timezone) {
		ZoneId requestedZone = requestedZone(timezone);
		var journey = journeys.current(userId, bearerToken, correlationId);
		var day = plans.currentDay(userId, requestedZone).orElse(null);
		if (day == null) {
			if (plans.currentOrDraftStatus(userId).isPresent()) {
				return new HomeNextStepView(POLICY_VERSION, journey, null, null);
			}
			var completed = plans.latestHistoryStatus(userId)
					.filter(value -> "COMPLETED".equals(value.status()))
					.map(value -> new HomeCompletedPlanView(value.supportPlanId(), value.completedAt())).orElse(null);
			return new HomeNextStepView(POLICY_VERSION, journey, null, completed);
		}
		var plan = day.plan();
		LocalDate date = day.localDate();
		var today = day.occurrences().stream()
				.filter(value -> date.equals(value.localDate()) && !"CANCELLED".equals(value.state())).toList();
		var orderedActions = orderedActions(plan);
		var next = today.stream().filter(value -> List.of("SCHEDULED", "OVERDUE").contains(value.displayState()))
				.min(Comparator.comparing(ActionOccurrenceView::scheduledAt, Comparator.nullsLast(Comparator.naturalOrder()))
						.thenComparingInt(value -> orderedActions.get(value.actionId()).order())).orElse(null);
		Focus focus = focus(plan, today, next);
		var step = new HomePlanStepView(plan.supportPlanId(), plan.status(), focus,
				plan.cycle() == null ? null : plan.cycle().reviewDueOn(),
				plan.cycle() == null ? null : plan.cycle().currentDay(),
				plan.cycle() == null ? null : plan.cycle().lengthDays(), date, day.timeZone(),
				new HomeTodayView(today.size(), (int) today.stream().filter(value -> "COMPLETED".equals(value.state())).count()),
				focus == Focus.DO_TODAY ? nextOccurrence(next, orderedActions.get(next.actionId()).action()) : null);
		return new HomeNextStepView(POLICY_VERSION, journey, step, null);
	}

	private ZoneId requestedZone(String timezone) {
		if (timezone == null) return null;
		if (timezone.length() > 64 || !ZoneId.getAvailableZoneIds().contains(timezone)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "timezone must be a valid IANA time zone");
		}
		return ZoneId.of(timezone);
	}

	private Focus focus(SupportPlanV3View plan, List<ActionOccurrenceView> today, ActionOccurrenceView next) {
		if ("PAUSED".equals(plan.status())) return Focus.RESUME_PAUSED;
		if (plan.cycle() != null && "REVIEW_DUE".equals(plan.cycle().state())) return Focus.REVIEW_DUE;
		if (next != null) return Focus.DO_TODAY;
		if (!today.isEmpty() && today.stream().allMatch(value -> List.of("COMPLETED", "SKIPPED").contains(value.state()))) {
			return Focus.TODAY_DONE;
		}
		return Focus.NOTHING_TODAY;
	}

	private Map<String, OrderedAction> orderedActions(SupportPlanV3View plan) {
		var result = new LinkedHashMap<String, OrderedAction>();
		plan.focuses().stream().sorted(Comparator.comparingInt(PlanFocusView::ordinal)).forEach(focus ->
				focus.actions().stream().sorted(Comparator.comparingInt(PlanActionView::ordinal)).forEach(action ->
						result.put(action.actionId(), new OrderedAction(result.size(), action))));
		return result;
	}

	private HomeNextOccurrenceView nextOccurrence(ActionOccurrenceView occurrence, PlanActionView action) {
		Short duration = action.schedules().stream()
				.filter(schedule -> schedule.scheduleId().equals(occurrence.scheduleId())
						&& schedule.version() == occurrence.scheduleVersion())
				.findFirst().map(schedule -> schedule.estimatedDurationMinutes()).orElse(null);
		return new HomeNextOccurrenceView(occurrence.occurrenceId(), occurrence.actionId(), occurrence.actionType(),
				action.presentation().title(), occurrence.displayState(), occurrence.scheduledAt(), duration);
	}

	public enum Focus { RESUME_PAUSED, REVIEW_DUE, DO_TODAY, TODAY_DONE, NOTHING_TODAY }

	public record HomeNextStepView(String homePolicyVersion, JourneyView journey, HomePlanStepView plan,
			HomeCompletedPlanView lastCompletedPlan) { }
	public record HomePlanStepView(UUID supportPlanId, String status, Focus focus, LocalDate reviewDueOn,
			Integer cycleDay, Integer cycleLengthDays, LocalDate localDate, String timeZone, HomeTodayView today,
			HomeNextOccurrenceView nextOccurrence) { }
	public record HomeTodayView(int total, int completed) { }
	public record HomeNextOccurrenceView(UUID occurrenceId, String actionId, String actionType, String title,
			String displayState, Instant scheduledAt, Short estimatedDurationMinutes) { }
	public record HomeCompletedPlanView(UUID supportPlanId, Instant completedAt) { }
	private record OrderedAction(int order, PlanActionView action) { }
}
