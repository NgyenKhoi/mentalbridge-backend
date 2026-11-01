package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class SupportPlanCycle {

	static final String POLICY = "support-plan-cycle-v1";
	static final int LENGTH_DAYS = 14;
	static final String DEFAULT_TIMEZONE = "Asia/Ho_Chi_Minh";

	static boolean applies(SupportPlanEntity plan) {
		return plan.modelVersion() == 3 && NativeSupportPlanWriter.PROPOSAL_POLICY.equals(plan.planPolicyVersion());
	}

	static LocalDate localDate(Instant now, String timezone) {
		return now.atZone(ZoneId.of(timezone)).toLocalDate();
	}

	static int pausedDays(SupportPlanEntity plan, LocalDate today) {
		int completed = plan.cyclePausedDays() == null ? 0 : plan.cyclePausedDays();
		LocalDate pausedOn = plan.cyclePausedOn();
		if (pausedOn == null) return completed;
		// A pause that starts once the review is already due never moves the review date.
		LocalDate started = plan.cycleStartedOn();
		if (started != null && !pausedOn.isBefore(started.plusDays(LENGTH_DAYS - 1L + completed))) return completed;
		return completed + Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(pausedOn, today)));
	}

	static View view(SupportPlanEntity plan, Instant now) {
		if (!applies(plan)) return null;
		String timezone = plan.cycleTimezone() == null ? DEFAULT_TIMEZONE : plan.cycleTimezone();
		Instant closedAt = plan.completedAt() != null ? plan.completedAt()
				: plan.supersededAt() != null ? plan.supersededAt() : plan.discardedAt();
		LocalDate today = localDate(closedAt == null ? now : closedAt, timezone);
		int pausedDays = pausedDays(plan, today);
		LocalDate started = plan.cycleStartedOn();
		LocalDate due = started == null ? null : started.plusDays(LENGTH_DAYS - 1L + pausedDays);
		Integer day = started == null ? null : (int) Math.min(LENGTH_DAYS,
				Math.max(1, ChronoUnit.DAYS.between(started, today) + 1 - pausedDays));
		String state = closedAt != null ? "CLOSED" : "DRAFT".equals(plan.status()) ? "NOT_STARTED"
				: "PAUSED".equals(plan.status()) ? "PAUSED"
				: due != null && !today.isBefore(due) ? "REVIEW_DUE" : "IN_PROGRESS";
		return new View(POLICY, LENGTH_DAYS, timezone, plan.cycleNumber() == null ? 1 : plan.cycleNumber(),
				started, due, day, pausedDays, state);
	}

	public record View(String policyVersion, int lengthDays, String timezone, int cycleNumber,
			LocalDate startedOn, LocalDate reviewDueOn, Integer currentDay, int pausedDays, String state) { }
}
