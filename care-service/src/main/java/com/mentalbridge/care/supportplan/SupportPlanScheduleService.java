package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;

@Service
public class SupportPlanScheduleService {

	private final SupportPlanRepository plans;
	private final SupportPlanSlotRepository slots;
	private final PlanActionRepository actions;
	private final SupportPlanActivityScheduleRepository schedules;
	private final SupportPlanScheduleCommandRepository commands;
	private final SupportPlanActivityOccurrenceService occurrences;
	private final UserProfileRepository profiles;
	private final Clock clock;

	public SupportPlanScheduleService(SupportPlanRepository plans, SupportPlanSlotRepository slots,
			PlanActionRepository actions, SupportPlanActivityScheduleRepository schedules,
			SupportPlanScheduleCommandRepository commands,
			SupportPlanActivityOccurrenceService occurrences, UserProfileRepository profiles, Clock clock) {
		this.plans = plans;
		this.slots = slots;
		this.actions = actions;
		this.schedules = schedules;
		this.commands = commands;
		this.occurrences = occurrences;
		this.profiles = profiles;
		this.clock = clock;
	}

	@Transactional
	public ScheduleUpdateResult update(UUID userId, UUID supportPlanId, UUID actionId,
			long expectedPlanVersion, String idempotencyKey, ScheduleCommand command) {
		return update(userId, supportPlanId, actionId, expectedPlanVersion, idempotencyKey, command,
				hash(supportPlanId, actionId, command), null);
	}

	@Transactional
	ScheduleUpdateResult updateReviewed(UUID userId, UUID supportPlanId, UUID actionId,
			long expectedPlanVersion, String idempotencyKey, ScheduleCommand command,
			String requestHash, Runnable guard) {
		return update(userId, supportPlanId, actionId, expectedPlanVersion, idempotencyKey, command, requestHash, guard);
	}

	private ScheduleUpdateResult update(UUID userId, UUID supportPlanId, UUID actionId,
			long expectedPlanVersion, String idempotencyKey, ScheduleCommand command,
			String requestHash, Runnable guard) {
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		if (plans.findRequest(userId, idempotencyKey).isPresent() || plans.findCommand(userId, idempotencyKey).isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
					"Idempotency-Key was already used with a different plan command");
		}
		var replay = commands.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElse(null);
		if (replay != null) {
			if (!replay.requestHash().equals(requestHash)) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"Idempotency-Key was already used with a different schedule command");
			}
			return new ScheduleUpdateResult(replay.supportPlanId(), replay.resultingPlanVersion(),
					replay.resultingScheduleId(), replay.resultingScheduleVersion(), true);
		}

		var plan = plans.findByIdAndUserIdForUpdate(supportPlanId, userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND",
						"SupportPlan was not found"));
		if (!List.of("DRAFT", "ACTIVE").contains(plan.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_SCHEDULE_NOT_MUTABLE",
					"Only a draft or active SupportPlan accepts schedule changes");
		}
		if (plan.version() != expectedPlanVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "SUPPORT_PLAN_VERSION_MISMATCH",
					"SupportPlan version does not match If-Match");
		}
		if (guard != null) guard.run();

		ActionTarget target = target(plan, userId, actionId);
		List<SupportPlanActivityScheduleEntity> history = target.slot() != null
				? schedules.findBySupportPlanIdAndSourceSlotKeyForUpdate(plan.id(), target.slot().slotKey())
				: schedules.findBySourceActionIdAndUserIdForUpdate(target.action().id(), userId);
		var current = history.stream().filter(value -> !"ENDED".equals(value.status())).findFirst().orElse(null);
		int nextScheduleVersion = history.stream().map(SupportPlanActivityScheduleEntity::scheduleVersion)
				.max(Comparator.naturalOrder()).orElse(0) + 1;

		NormalizedSchedule desired = command.schedule() == null ? null
				: normalize(command.schedule(), plan.status(), target.actionType(), now);
		if (desired == null && current == null) {
			recordCommand(userId, idempotencyKey, requestHash, plan, actionId,
					expectedPlanVersion, null, now);
			return new ScheduleUpdateResult(plan.id(), plan.version(), null, null, false);
		}
		if (desired != null && current != null && same(current, desired)) {
			recordCommand(userId, idempotencyKey, requestHash, plan, actionId,
					expectedPlanVersion, current, now);
			return new ScheduleUpdateResult(plan.id(), plan.version(), current.id(),
					current.scheduleVersion(), false);
		}

		if (current != null) {
			LocalDate cutoverDate = desired == null
					? now.atZone(zone(current.timezone())).toLocalDate().plusDays("ACTIVE".equals(plan.status()) ? 1 : 0)
					: desired.effectiveFrom();
			Instant cutover = occurrences.resolve(cutoverDate, LocalTime.MIDNIGHT, current.timezone());
			current.end(cutoverDate.minusDays(1), now);
			schedules.flush();
			if ("ACTIVE".equals(plan.status())) {
				occurrences.scheduleChanged(current.id(), cutover, now);
			}
		}

		plan.actionChanged(now);
		plans.saveAndFlush(plan);

		SupportPlanActivityScheduleEntity resultSchedule = null;
		if (desired != null) {
			String status = "DRAFT".equals(plan.status()) ? "DRAFT" : "ACTIVE";
			if (target.slot() != null) {
				resultSchedule = SupportPlanActivityScheduleEntity.revisionForSlot(UUID.randomUUID(), plan,
						target.slot(), nextScheduleVersion,
						SupportPlanActivityOccurrenceService.SCHEDULE_POLICY_VERSION_V2,
						desired.recurrenceType(), desired.daysMask(), desired.preferredDaypart(),
						desired.estimatedDurationMinutes(), desired.localTime(), desired.timezone(),
						desired.effectiveFrom(), desired.effectiveUntil(), desired.reminderEnabled(),
						status, now);
			}
			else {
				resultSchedule = SupportPlanActivityScheduleEntity.revisionForAction(UUID.randomUUID(), plan,
						target.action(), nextScheduleVersion,
						SupportPlanActivityOccurrenceService.SCHEDULE_POLICY_VERSION_V2,
						desired.recurrenceType(), desired.daysMask(), desired.preferredDaypart(),
						desired.estimatedDurationMinutes(), desired.localTime(), desired.timezone(),
						desired.effectiveFrom(), desired.effectiveUntil(), desired.reminderEnabled(),
						status, now);
			}
			schedules.saveAndFlush(resultSchedule);
			if ("ACTIVE".equals(plan.status())) {
				occurrences.generateRollingHorizon(plan.id(), now);
			}
		}

		recordCommand(userId, idempotencyKey, requestHash, plan, actionId,
				expectedPlanVersion, resultSchedule, now);
		return new ScheduleUpdateResult(plan.id(), plan.version(),
				resultSchedule == null ? null : resultSchedule.id(),
				resultSchedule == null ? null : resultSchedule.scheduleVersion(), false);
	}

	private void recordCommand(UUID userId, String idempotencyKey, String requestHash,
			SupportPlanEntity plan, UUID actionId, long expectedPlanVersion,
			SupportPlanActivityScheduleEntity resultSchedule, Instant now) {
		commands.saveAndFlush(new SupportPlanScheduleCommandEntity(userId, idempotencyKey, requestHash,
				plan.id(), actionId, expectedPlanVersion, plan.version(),
				resultSchedule == null ? null : resultSchedule.id(),
				resultSchedule == null ? null : resultSchedule.scheduleVersion(), now));
	}

	@Transactional
	void preserveReviewedDraft(SupportPlanEntity current, SupportPlanEntity draft, Instant now) {
		var previousActions = actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(current.id(), current.userId())
				.stream().filter(action -> !"ENDED".equals(action.status())
						&& !List.of("REASSESSMENT_SCREENING", "PROFESSIONAL_SUPPORT").contains(action.actionType()))
				.collect(java.util.stream.Collectors.toMap(this::intentKey, action -> action));
		var previousSlots = slots.findBySupportPlanIdOrderByOrdinal(current.id()).stream()
				.filter(slot -> slot.selectedResource() != null)
				.collect(java.util.stream.Collectors.toMap(SupportPlanSlotEntity::slotKey, slot -> slot));
		var previousSchedules = schedules.findBySupportPlanIdForUpdate(current.id()).stream()
				.filter(schedule -> !"ENDED".equals(schedule.status())).toList();
		var defaults = schedules.findBySupportPlanIdForUpdate(draft.id());
		var removed = new ArrayList<SupportPlanActivityScheduleEntity>();
		var copied = new ArrayList<SupportPlanActivityScheduleEntity>();
		for (var action : actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(draft.id(), draft.userId())) {
			if (List.of("REASSESSMENT_SCREENING", "PROFESSIONAL_SUPPORT").contains(action.actionType())) continue;
			var previous = previousActions.get(intentKey(action));
			var previousSlot = "RESOURCE".equals(action.actionType()) ? previousSlots.get(action.provenanceReferenceId()) : null;
			if (previous == null && previousSlot == null) continue;
			var source = previousSchedules.stream().filter(schedule -> previous != null
					? previous.id().equals(schedule.sourceActionId())
					: previousSlot.slotKey().equals(schedule.sourceSlotKey())).findFirst().orElse(null);
			removed.addAll(defaults.stream().filter(schedule -> action.id().equals(schedule.sourceActionId())).toList());
			if (source == null) continue;
			if (!SupportPlanActivityOccurrenceService.SCHEDULE_POLICY_VERSION_V2.equals(source.schedulePolicyVersion())
					|| source.estimatedDurationMinutes() == null) {
				throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_POLICY_STALE",
						"The reviewed replacement cannot preserve an older schedule policy");
			}
			Short days = source.recurrenceDaysMask();
			if ("WEEKLY".equals(source.recurrenceType()) && days == null && source.recurrenceDayOfWeek() != null) {
				days = (short) (1 << (source.recurrenceDayOfWeek() - 1));
			}
			copied.add(SupportPlanActivityScheduleEntity.revisionForAction(UUID.randomUUID(), draft, action, 1,
					source.schedulePolicyVersion(), source.recurrenceType(), days, source.preferredDaypart(),
					source.estimatedDurationMinutes(), source.localTime(), source.timezone(), source.effectiveFrom(),
					source.effectiveUntil(), source.reminderEnabled(), "DRAFT", now));
		}
		schedules.deleteAll(removed);
		schedules.flush();
		schedules.saveAll(copied);
		schedules.flush();
	}

	private String intentKey(PlanActionEntity action) {
		return "RESOURCE".equals(action.actionType()) ? "RESOURCE:" + action.provenanceReferenceId()
				: action.actionType() + ":" + action.sourceOwner() + ":" + action.sourceKind() + ":" + action.sourceId();
	}

	boolean reviewedDraftSchedulesMatch(SupportPlanV3Service.SupportPlanV3View current,
			SupportPlanV3Service.SupportPlanV3View draft) {
		var expected = reviewedSchedules(current);
		return expected != null && expected.equals(reviewedSchedules(draft));
	}

	private java.util.Map<String, List<ReviewedSchedule>> reviewedSchedules(SupportPlanV3Service.SupportPlanV3View plan) {
		var values = new java.util.HashMap<String, List<ReviewedSchedule>>();
		for (var focus : plan.focuses()) {
			for (var action : focus.actions()) {
				if ("ENDED".equals(action.status()) || List.of("PROFESSIONAL_SUPPORT", "REASSESSMENT_SCREENING")
						.contains(action.actionType())) continue;
				var source = action.source();
				String intent = "RESOURCE".equals(action.actionType()) ? "RESOURCE:" + action.provenance().referenceId()
						: action.actionType() + ":" + (source == null ? "" : source.ownerDomain() + ":" + source.sourceKind() + ":" + source.sourceId());
				var schedule = action.schedules().stream().filter(item -> !"ENDED".equals(item.status())).map(item ->
						new ReviewedSchedule(item.schedulePolicyVersion(), item.recurrenceType(),
								item.daysOfWeek().stream().sorted().toList(), item.preferredDaypart(), item.localTime(),
								item.estimatedDurationMinutes(), item.timezone(), item.effectiveFrom(), item.effectiveUntil(),
								item.reminderEnabled())).sorted(Comparator.comparing(ReviewedSchedule::toString)).toList();
				if (values.putIfAbsent(intent, schedule) != null) return null;
			}
		}
		return values;
	}

	private ActionTarget target(SupportPlanEntity plan, UUID userId, UUID actionId) {
		if (plan.modelVersion() == 2) {
			var slot = slots.findById(actionId).filter(value -> value.supportPlanId().equals(plan.id())
					&& value.selectedResource() != null).orElseThrow(() ->
							new ApiException(HttpStatus.NOT_FOUND, "PLAN_ACTION_NOT_FOUND",
									"PlanAction was not found"));
			return new ActionTarget(slot, null, "RESOURCE");
		}
		var action = actions.findByIdAndUserIdForUpdate(actionId, userId).filter(value ->
				value.supportPlanId().equals(plan.id())).orElseThrow(() ->
						new ApiException(HttpStatus.NOT_FOUND, "PLAN_ACTION_NOT_FOUND",
								"PlanAction was not found"));
		if ("ENDED".equals(action.status())
				|| ("ACTIVE".equals(plan.status()) && !"ACTIVE".equals(action.status()))) {
			throw new ApiException(HttpStatus.CONFLICT, "PLAN_ACTION_SCHEDULE_NOT_MUTABLE",
					"PlanAction does not accept schedule changes in its current state");
		}
		if (List.of("PROFESSIONAL_SUPPORT", "SELF_REPORT_REVIEW").contains(action.actionType())) {
			throw new ApiException(HttpStatus.CONFLICT, "PLAN_ACTION_UNSCHEDULABLE",
					"This PlanAction is review-only and does not accept recurrence");
		}
		return new ActionTarget(null, action, action.actionType());
	}

	private NormalizedSchedule normalize(ScheduleInput input, String planStatus, String actionType, Instant now) {
		if (!List.of("ONCE", "DAILY", "WEEKLY").contains(input.recurrenceType())) {
			throw invalid("recurrenceType", "Schedule cadence is not supported");
		}
		var days = input.daysOfWeek() == null ? List.<Integer>of() : input.daysOfWeek();
		var uniqueDays = new LinkedHashSet<>(days);
		if (uniqueDays.size() != days.size() || days.stream().anyMatch(day -> day == null || day < 1 || day > 7)
				|| ("WEEKLY".equals(input.recurrenceType()) && days.isEmpty())
				|| (!"WEEKLY".equals(input.recurrenceType()) && !days.isEmpty())) {
			throw invalid("daysOfWeek", "Weekly cadence requires one through seven unique ISO weekdays");
		}
		if (input.preferredDaypart() != null && !List.of("MORNING", "AFTERNOON", "EVENING", "FLEXIBLE")
				.contains(input.preferredDaypart())) {
			throw invalid("preferredDaypart", "Preferred daypart is not supported");
		}
		if (input.localTime() == null) throw invalid("localTime", "A resolved local time is required");
		if (input.estimatedDurationMinutes() < 5 || input.estimatedDurationMinutes() > 180
				|| input.estimatedDurationMinutes() % 5 != 0) {
			throw invalid("estimatedDurationMinutes", "Duration must be 5 through 180 minutes in five-minute steps");
		}
		ZoneId zone = zone(input.timezone());
		LocalDate today = now.atZone(zone).toLocalDate();
		LocalDate minimum = "ACTIVE".equals(planStatus) ? today.plusDays(1) : today;
		if (input.effectiveFrom() == null || input.effectiveFrom().isBefore(minimum)
				|| input.effectiveUntil() != null && input.effectiveUntil().isBefore(input.effectiveFrom())) {
			throw invalid("effectiveFrom", "Schedule dates are outside the supported future window");
		}
		if ("REASSESSMENT_SCREENING".equals(actionType) && !"ONCE".equals(input.recurrenceType())) {
			throw new ApiException(HttpStatus.CONFLICT, "PLAN_ACTION_UNSCHEDULABLE",
					"Reassessment screening accepts a one-time schedule only");
		}
		short mask = 0;
		for (int day : uniqueDays) mask |= (short) (1 << (day - 1));
		return new NormalizedSchedule(input.recurrenceType(),
				"WEEKLY".equals(input.recurrenceType()) ? mask : null,
				input.preferredDaypart(), input.localTime(), input.estimatedDurationMinutes(),
				zone.getId(), input.effectiveFrom(), input.effectiveUntil(), input.reminderEnabled());
	}

	private boolean same(SupportPlanActivityScheduleEntity stored, NormalizedSchedule desired) {
		return Objects.equals(stored.recurrenceType(), desired.recurrenceType())
				&& Objects.equals(stored.recurrenceDaysMask(), desired.daysMask())
				&& Objects.equals(stored.preferredDaypart(), desired.preferredDaypart())
				&& Objects.equals(stored.localTime(), desired.localTime())
				&& Objects.equals(stored.estimatedDurationMinutes(),
						(short) desired.estimatedDurationMinutes())
				&& Objects.equals(stored.timezone(), desired.timezone())
				&& Objects.equals(stored.effectiveFrom(), desired.effectiveFrom())
				&& Objects.equals(stored.effectiveUntil(), desired.effectiveUntil())
				&& stored.reminderEnabled() == desired.reminderEnabled();
	}

	private String hash(UUID supportPlanId, UUID actionId, ScheduleCommand command) {
		var values = new ArrayList<String>();
		values.add(supportPlanId.toString());
		values.add(actionId.toString());
		if (command.schedule() == null) {
			values.add("null");
		}
		else {
			var schedule = command.schedule();
			values.add(Objects.toString(schedule.recurrenceType(), ""));
			values.add(schedule.daysOfWeek() == null ? "" : schedule.daysOfWeek().stream().sorted()
					.map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
			values.add(Objects.toString(schedule.preferredDaypart(), ""));
			values.add(Objects.toString(schedule.localTime(), ""));
			values.add(Integer.toString(schedule.estimatedDurationMinutes()));
			values.add(Objects.toString(schedule.timezone(), ""));
			values.add(Objects.toString(schedule.effectiveFrom(), ""));
			values.add(Objects.toString(schedule.effectiveUntil(), ""));
			values.add(Boolean.toString(schedule.reminderEnabled()));
		}
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ZoneId zone(String timezone) {
		try {
			if (timezone == null || timezone.isBlank() || timezone.length() > 64) throw new IllegalArgumentException();
			return ZoneId.of(timezone);
		}
		catch (Exception exception) {
			throw invalid("timezone", "Timezone must be a valid IANA timezone");
		}
	}

	private void lockOwner(UUID userId) {
		if (profiles.findByIdForUpdate(userId).isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND",
					"Care profile was not found");
		}
	}

	private ApiException invalid(String field, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "SUPPORT_PLAN_SCHEDULE_INVALID",
				field + ": " + message);
	}

	private record ActionTarget(SupportPlanSlotEntity slot, PlanActionEntity action, String actionType) { }
	private record NormalizedSchedule(String recurrenceType, Short daysMask, String preferredDaypart,
			LocalTime localTime, int estimatedDurationMinutes, String timezone,
			LocalDate effectiveFrom, LocalDate effectiveUntil, boolean reminderEnabled) { }
	private record ReviewedSchedule(String policy, String recurrenceType, List<Integer> daysOfWeek,
			String preferredDaypart, LocalTime localTime, Short durationMinutes, String timezone,
			LocalDate effectiveFrom, LocalDate effectiveUntil, boolean reminderEnabled) { }
	public record ScheduleCommand(ScheduleInput schedule) { }
	public record ScheduleInput(String recurrenceType, List<Integer> daysOfWeek,
			String preferredDaypart, LocalTime localTime, int estimatedDurationMinutes,
			String timezone, LocalDate effectiveFrom, LocalDate effectiveUntil,
			boolean reminderEnabled) { }
	public record ScheduleUpdateResult(UUID supportPlanId, long planVersion,
			UUID scheduleId, Integer scheduleVersion, boolean replayed) { }
}
