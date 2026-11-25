package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trusted service read for Content's daily SupportPlan reminder (MB-855). Answers only
 * whether the owner's current ACTIVE plan has a SCHEDULED, reminder-enabled occurrence
 * due today in that occurrence's own timezone; no plan, action or clinical detail leaves.
 */
@Service
public class SupportPlanReminderEligibilityService {

	private final SupportPlanRepository plans;
	private final SupportPlanActivityOccurrenceService activities;
	private final SupportPlanActivityScheduleRepository schedules;
	private final PlanActionRepository actions;

	SupportPlanReminderEligibilityService(SupportPlanRepository plans, SupportPlanActivityOccurrenceService activities,
			SupportPlanActivityScheduleRepository schedules, PlanActionRepository actions) {
		this.plans = plans;
		this.activities = activities;
		this.schedules = schedules;
		this.actions = actions;
	}

	@Transactional
	public boolean eligible(UUID userId, Instant asOf) {
		var current = plans.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "PAUSED"));
		if (current.isEmpty() || !"ACTIVE".equals(current.get().status())) return false;
		// Occurrence local dates span at most one day either side of the UTC date.
		LocalDate day = asOf.atZone(ZoneOffset.UTC).toLocalDate();
		var window = activities.loadV3Window(userId, day.minusDays(1), day.plusDays(1));
		if (!"ACTIVE".equals(window.plan().status())) return false;
		var cycle = SupportPlanCycle.view(window.plan(), asOf);
		if (cycle != null && "REVIEW_DUE".equals(cycle.state())) return false;
		return window.occurrences().stream().anyMatch(occurrence -> "SCHEDULED".equals(occurrence.state())
				&& occurrence.localDate().equals(asOf.atZone(ZoneId.of(occurrence.timezone())).toLocalDate())
				&& schedules.findById(occurrence.activityScheduleId())
						.filter(schedule -> "ACTIVE".equals(schedule.status()) && schedule.reminderEnabled()).isPresent()
				&& (occurrence.sourceActionId() == null || actions.findById(occurrence.sourceActionId())
						.filter(action -> "ACTIVE".equals(action.status())).isPresent()));
	}
}
