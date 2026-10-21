package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.mentalbridge.care.reassessment.AdaptiveReviewEvidenceService.OccurrenceEvidence;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.Change;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.Contradiction;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.EvidenceRef;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.Option;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.Pattern;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationSnapshot;
import com.mentalbridge.care.supportplan.SupportPlanScheduleService.ScheduleCommand;
import com.mentalbridge.care.supportplan.SupportPlanScheduleService.ScheduleInput;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionScheduleView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanActionView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.SupportPlanV3View;

@Component
class AdaptiveSupportPlanReviewPolicy {
	static final String POLICY = "support-plan-review-v1";
	static final List<String> KINDS = List.of("KEEP_CURRENT", "LOWER_WORKLOAD", "DIFFERENT_TIME",
			"ALTERNATIVE_ACTION", "DIFFERENT_FOCUS", "PROFESSIONAL_SUPPORT_NEXT_STEP");

	List<Pattern> patterns(SupportPlanV3View plan, List<OccurrenceEvidence> occurrences) {
		var grouped = new LinkedHashMap<String, Map<UUID, OccurrenceEvidence>>();
		for (var item : occurrences) {
			if (!plan.supportPlanId().equals(item.supportPlanId()) || item.actionId() == null) continue;
			var codes = new ArrayList<String>();
			if (List.of("NOT_ENOUGH_TIME", "LOW_ENERGY", "NOT_A_GOOD_FIT").contains(Objects.toString(item.barrierCode(), ""))) {
				codes.add(item.barrierCode());
			}
			if ("HELPFUL".equals(item.helpfulness())) codes.add("HELPFUL");
			for (String code : codes) grouped.computeIfAbsent(item.actionId() + ":" + code,
					ignored -> new LinkedHashMap<>()).put(item.occurrenceId(), item);
		}
		var actions = actions(plan);
		var result = new ArrayList<Pattern>();
		for (var group : grouped.entrySet()) {
			var values = group.getValue().values().stream().sorted(Comparator.comparing(OccurrenceEvidence::occurrenceId)).toList();
			if (values.size() < 2 || values.size() > 31) continue;
			var action = actions.stream().filter(item -> item.actionId().equals(values.getFirst().actionId().toString()))
					.findFirst().orElse(null);
			if (action == null || "ENDED".equals(action.status())) continue;
			String code = group.getKey().substring(group.getKey().indexOf(':') + 1);
			var ids = values.stream().map(OccurrenceEvidence::occurrenceId).toList();
			result.add(new Pattern(digest(group.getKey() + ids), "HELPFUL".equals(code) ? "REPEATED_HELPFUL" : "REPEATED_BARRIER",
					code, values.getFirst().actionId(), action.presentation().title(), ids.size(), ids));
		}
		return result.stream().sorted(Comparator.comparing(Pattern::patternId)).limit(20).toList();
	}

	List<Contradiction> contradictions(ReassessmentSummaryView summary) {
		if (summary == null) return List.of();
		var trends = summary.screening().trends().stream().filter(item -> item.current() != null
				&& List.of("INCREASED", "DECREASED").contains(Objects.toString(item.direction(), ""))).toList();
		if (trends.size() != 2 || trends.get(0).direction().equals(trends.get(1).direction())) return List.of();
		var refs = trends.stream().map(item -> new EvidenceRef("PHQ9".equals(item.instrument())
				? "SCREENING_PHQ9" : "SCREENING_GAD7", item.current().assessmentId().toString())).toList();
		return List.of(new Contradiction(digest(refs.toString()), "INSTRUMENT_DIRECTIONS_DIFFER", refs));
	}

	Candidate lowerWorkload(SupportPlanV3View plan, List<Pattern> patterns, PersonalizationSnapshot preferences,
			List<EvidenceRef> conflicts, Instant now) {
		for (var pattern : patterns) {
			if (!List.of("NOT_ENOUGH_TIME", "LOW_ENERGY").contains(pattern.code())) continue;
			var action = action(plan, pattern.actionId());
			var schedule = current(action);
			if (schedule == null || !List.of("DAILY", "WEEKLY").contains(schedule.recurrenceType())) continue;
			int minutes = schedule.estimatedDurationMinutes() == null ? 5 : schedule.estimatedDurationMinutes();
			String recurrence = schedule.recurrenceType();
			var days = schedule.daysOfWeek();
			Change change;
			if (minutes > 5) {
				change = new Change("ESTIMATED_DURATION_MINUTES", Integer.toString(minutes), Integer.toString(minutes - 5));
				minutes -= 5;
			}
			else if ("DAILY".equals(recurrence)) {
				recurrence = "WEEKLY";
				days = List.of(1, 3, 5);
				change = new Change("DAYS_PER_WEEK", "7", "3");
			}
			else if (days.size() > 1) {
				change = new Change("DAYS_PER_WEEK", Integer.toString(days.size()), Integer.toString(days.size() - 1));
				days = days.subList(0, days.size() - 1);
			}
			else continue;
			var input = input(schedule, recurrence, days, schedule.preferredDaypart(), schedule.localTime(), minutes, now);
			if (input == null || !fits(plan, action.actionId(), input, preferences, false)) continue;
			return new Candidate(option("LOWER_WORKLOAD", "UPDATE_SCHEDULE", pattern.actionId(), null,
					List.of("REPEATED_" + pattern.code(), "PRACTICAL_FIT_ONLY"), List.of(ref(pattern)), conflicts,
					List.of(change)), new ScheduleCommand(input), null, null);
		}
		return null;
	}

	Candidate differentTime(SupportPlanV3View plan, PersonalizationSnapshot preferences,
			List<EvidenceRef> conflicts, Instant now) {
		if (preferences == null || preferences.preferredDaypart() == null
				|| "FLEXIBLE".equals(preferences.preferredDaypart().name())) return null;
		String daypart = preferences.preferredDaypart().name();
		int hour = switch (daypart) { case "MORNING" -> 8; case "AFTERNOON" -> 14; default -> 19; };
		for (var action : actions(plan)) {
			var schedule = current(action);
			if (schedule == null || daypart.equals(schedule.preferredDaypart())
					|| !List.of("DAILY", "WEEKLY").contains(schedule.recurrenceType())) continue;
			for (int offset = 0; offset < 120; offset += 15) {
				var time = LocalTime.of(hour, 0).plusMinutes(offset);
				var input = input(schedule, schedule.recurrenceType(), schedule.daysOfWeek(), daypart, time,
						schedule.estimatedDurationMinutes() == null ? 5 : schedule.estimatedDurationMinutes(), now);
				if (input == null || !fits(plan, action.actionId(), input, preferences, true)) continue;
				var refs = List.of(new EvidenceRef("PREFERENCES", preferences.personalizationId().toString()));
				return new Candidate(option("DIFFERENT_TIME", "UPDATE_SCHEDULE", UUID.fromString(action.actionId()), null,
						List.of("EXPLICIT_TIME_PREFERENCE", "PRACTICAL_FIT_ONLY"), refs, conflicts,
						List.of(new Change("PREFERRED_DAYPART", schedule.preferredDaypart(), daypart),
								new Change("LOCAL_TIME", schedule.localTime().toString(), time.toString()))),
						new ScheduleCommand(input), null, null);
			}
		}
		return null;
	}

	private ScheduleInput input(ActionScheduleView schedule, String recurrence, List<Integer> days,
			String daypart, LocalTime time, int minutes, Instant now) {
		LocalDate from = now.atZone(ZoneId.of(schedule.timezone())).toLocalDate().plusDays(1);
		if (schedule.effectiveFrom().isAfter(from)) from = schedule.effectiveFrom();
		if (schedule.effectiveUntil() != null && schedule.effectiveUntil().isBefore(from)) return null;
		return new ScheduleInput(recurrence, days, daypart, time, minutes, schedule.timezone(), from,
				schedule.effectiveUntil(), schedule.reminderEnabled());
	}

	private boolean fits(SupportPlanV3View plan, String changedId, ScheduleInput desired,
			PersonalizationSnapshot preferences, boolean checkOverlap) {
		int budget = preferences == null || preferences.dailyTimeBudgetMinutes() == null ? 10 : preferences.dailyTimeBudgetMinutes();
		for (int day = 1; day <= 7; day++) {
			if (!runs(desired.recurrenceType(), desired.daysOfWeek(), day)) continue;
			int total = desired.estimatedDurationMinutes();
			for (var action : actions(plan)) {
				if (action.actionId().equals(changedId)) continue;
				var schedule = current(action);
				if (schedule == null || "ONCE".equals(schedule.recurrenceType())
						|| !runs(schedule.recurrenceType(), schedule.daysOfWeek(), day)) continue;
				int minutes = schedule.estimatedDurationMinutes() == null ? 5 : schedule.estimatedDurationMinutes();
				total += minutes;
				if (checkOverlap && (!schedule.timezone().equals(desired.timezone())
						|| desired.localTime().isBefore(schedule.localTime().plusMinutes(minutes))
						&& schedule.localTime().isBefore(desired.localTime().plusMinutes(desired.estimatedDurationMinutes())))) return false;
			}
			if (total > budget) return false;
		}
		return true;
	}

	private boolean runs(String recurrence, List<Integer> days, int day) {
		return "DAILY".equals(recurrence) || "WEEKLY".equals(recurrence) && days.contains(day);
	}

	Option option(String kind, String execution, UUID actionId, String focusId, List<String> reasons,
			List<EvidenceRef> evidence, List<EvidenceRef> conflicts, List<Change> changes) {
		String id = digest(POLICY + kind + execution + actionId + focusId + reasons + evidence + conflicts + changes);
		return new Option(id, kind, execution, actionId, focusId, reasons, evidence, conflicts, changes);
	}

	static String digest(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
	}

	static List<PlanActionView> actions(SupportPlanV3View plan) {
		return plan.focuses().stream().flatMap(focus -> focus.actions().stream()).filter(action -> !"ENDED".equals(action.status())).toList();
	}
	static PlanActionView action(SupportPlanV3View plan, UUID id) {
		return actions(plan).stream().filter(action -> action.actionId().equals(id.toString())).findFirst().orElseThrow();
	}
	static ActionScheduleView current(PlanActionView action) {
		return action.schedules().stream().filter(schedule -> !"ENDED".equals(schedule.status()))
				.max(Comparator.comparingInt(ActionScheduleView::version)).orElse(null);
	}
	static EvidenceRef ref(Pattern pattern) { return new EvidenceRef("PATTERN", pattern.patternId()); }

	record Candidate(Option option, ScheduleCommand schedule, SupportPlanPolicy.Proposal proposal, UUID evaluationId) { }
}
