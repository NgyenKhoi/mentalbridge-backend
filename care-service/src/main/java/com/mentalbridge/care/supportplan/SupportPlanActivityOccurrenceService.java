package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.EvidenceReference;

@Service
public class SupportPlanActivityOccurrenceService {

	static final String SCHEDULE_POLICY_VERSION = "support-plan-activity-schedule-v1";
	private static final int INITIAL_HORIZON_DAYS = 14;
	private static final int MAX_QUERY_DAYS = 31;
	private static final List<LocalTime> DEFAULT_TIMES = List.of(LocalTime.of(8, 0), LocalTime.of(10, 0),
			LocalTime.of(14, 0), LocalTime.of(18, 0), LocalTime.of(20, 0));

	private final SupportPlanRepository plans;
	private final SupportPlanSlotRepository slots;
	private final PlanActionRepository actions;
	private final SupportPlanActivityScheduleRepository schedules;
	private final SupportPlanActivityOccurrenceRepository occurrences;
	private final UserProfileRepository profiles;
	private final SupportPlanPersonalizationService personalizations;
	private final Clock clock;
	private final ObjectMapper objectMapper;
	private final PlanActionSourcePolicy actionPolicy = new PlanActionSourcePolicy();

	public SupportPlanActivityOccurrenceService(SupportPlanRepository plans, SupportPlanSlotRepository slots,
			PlanActionRepository actions, SupportPlanActivityScheduleRepository schedules,
			SupportPlanActivityOccurrenceRepository occurrences, UserProfileRepository profiles,
			SupportPlanPersonalizationService personalizations, Clock clock, ObjectMapper objectMapper) {
		this.plans = plans;
		this.slots = slots;
		this.actions = actions;
		this.schedules = schedules;
		this.occurrences = occurrences;
		this.profiles = profiles;
		this.personalizations = personalizations;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	@Transactional
	void activate(SupportPlanEntity plan, Instant now) {
		assertCurrentPersonalization(plan);
		String timezone = profiles.findTimezoneByAccountId(plan.userId()).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Care profile was not found"));
		ZoneId zone = zone(timezone);
		LocalDate start = now.atZone(zone).toLocalDate();
		var selectedSlots = slots.findBySupportPlanIdOrderByOrdinal(plan.id()).stream()
				.filter(slot -> slot.selectedResource() != null).toList();
		var personalization = plan.personalizationId() == null ? null
				: personalizations.snapshot(plan.personalizationId(), plan.userId(), plan.personalizationRevision())
						.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
								"SUPPORT_PLAN_PERSONALIZATION_INVALID",
								"SupportPlan personalization provenance is unavailable"));
		if (schedules.findBySupportPlanIdOrderByOrdinal(plan.id()).isEmpty()) {
			for (SupportPlanSlotEntity slot : selectedSlots) {
				var resource = slot.selectedResource();
				boolean daily = List.of("BREATHING", "MEDITATION", "JOURNALING")
						.contains(resource.category());
				var schedule = new SupportPlanActivityScheduleEntity(UUID.randomUUID(), plan.id(), plan.userId(),
						slot.ordinal(), plan.version(), slot, daily ? "DAILY" : "WEEKLY",
						daily ? null : (short) start.getDayOfWeek().getValue(),
						defaultTime(slot.ordinal(), personalization), timezone, start, now);
				schedules.save(schedule);
			}
			schedules.flush();
		}
		generate(plan.id(), start, start.plusDays(INITIAL_HORIZON_DAYS - 1), now);
	}

	private void assertCurrentPersonalization(SupportPlanEntity plan) {
		var current = personalizations.currentSnapshot(plan.userId()).orElse(null);
		boolean changed = plan.personalizationId() == null
				? current != null && current.provided()
				: current == null || !Objects.equals(plan.personalizationId(), current.personalizationId())
						|| !Objects.equals(plan.personalizationRevision(), current.revision());
		if (changed) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_PERSONALIZATION_REVIEW_REQUIRED",
					"SupportPlan preferences changed after this draft was created; discard and regenerate the draft before activation");
		}
	}

	private LocalTime defaultTime(int ordinal,
			SupportPlanPersonalizationService.PersonalizationSnapshot personalization) {
		if (personalization == null || personalization.preferredDaypart() == null
				|| personalization.preferredDaypart() == SupportPlanPersonalizationService.PreferredDaypart.FLEXIBLE) {
			return DEFAULT_TIMES.get(ordinal - 1);
		}
		int baseHour = switch (personalization.preferredDaypart()) {
			case MORNING -> 8;
			case AFTERNOON -> 13;
			case EVENING -> 18;
			case FLEXIBLE -> throw new IllegalStateException("Flexible daypart uses compatibility defaults");
		};
		return LocalTime.of(Math.min(baseHour + ordinal - 1, 21), 0);
	}

	@Transactional
	public OccurrenceListView list(UUID userId, LocalDate from, LocalDate through) {
		var plan = plans.findByUserIdAndStatusInForUpdate(userId, List.of("ACTIVE", "PAUSED")).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_CURRENT_NOT_FOUND",
						"Current SupportPlan was not found"));
		if (plan.modelVersion() != 2) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED",
					"This endpoint supports resource-slot SupportPlans only");
		}
		validateWindow(userId, from, through);
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		if ("ACTIVE".equals(plan.status())) {
			if (schedules.findBySupportPlanIdOrderByOrdinal(plan.id()).isEmpty()) {
				activate(plan, now);
			}
			generate(plan.id(), from, through, now);
		}
		var values = occurrences
				.findBySupportPlanIdAndUserIdAndLocalDateBetweenOrderByScheduledAtAscIdAsc(plan.id(), userId,
						from, through)
				.stream().map(value -> view(value, now)).toList();
		return new OccurrenceListView(plan.id(), plan.status(), SCHEDULE_POLICY_VERSION, from, through,
				values, "SELF_REPORTED_WELLBEING_ACTIVITY_NOT_TREATMENT_ADHERENCE");
	}

	@Transactional
	V3Window loadV3Window(UUID userId, LocalDate from, LocalDate through) {
		var plan = plans.findByUserIdAndStatusInForUpdate(userId, List.of("ACTIVE", "PAUSED")).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_CURRENT_NOT_FOUND",
						"Current SupportPlan was not found"));
		validateWindow(userId, from, through);
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		if ("ACTIVE".equals(plan.status())) {
			if (plan.modelVersion() == 2 && schedules.findBySupportPlanIdOrderByOrdinal(plan.id()).isEmpty()) {
				activate(plan, now);
			}
			generate(plan.id(), from, through, now);
		}
		var values = occurrences
				.findBySupportPlanIdAndUserIdAndLocalDateBetweenOrderByScheduledAtAscIdAsc(plan.id(), userId,
						from, through);
		return new V3Window(plan, values);
	}

	/**
	 * Trusted application boundary for an authoritative source-domain fact. The
	 * caller must have authenticated the source event and resolved its subject and
	 * source link; this method rechecks both against the locked Care action plus the
	 * native action type, fact shape, and one-fact/one-occurrence rule.
	 */
	@Transactional
	public ActionOccurrenceCompletion completeFromOwnerFact(UUID userId, UUID occurrenceId,
			AuthoritativeOwnerFact authoritativeFact, UUID correlationId) {
		var authorityLink = occurrences.findAuthorityLinkByIdAndUserId(occurrenceId, userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "OCCURRENCE_NOT_FOUND",
						"SupportPlan occurrence was not found"));
		var plan = plans.findByIdAndUserIdForUpdate(authorityLink.getSupportPlanId(), userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"));
		if (plan.modelVersion() != 3 || authorityLink.getSourceActionId() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED",
					"Authoritative completion facts apply to native action SupportPlans only");
		}
		var action = actions.findByIdAndUserIdForUpdate(authorityLink.getSourceActionId(), userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "PLAN_ACTION_NOT_FOUND", "PlanAction was not found"));
		var occurrence = occurrences.findByIdAndUserIdForUpdate(occurrenceId, userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "OCCURRENCE_NOT_FOUND",
						"SupportPlan occurrence was not found"));
		if (occurrence.sourceActionVersion() == null || occurrence.sourceActionType() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED",
					"Authoritative completion facts apply to native action SupportPlans only");
		}
		if (authoritativeFact == null || authoritativeFact.fact() == null) {
			throw invalidCompletionEvidence("Completion evidence is required");
		}
		validateFactAuthority(action, authoritativeFact);
		var fact = authoritativeFact.fact();
		var evidence = new EvidenceReference(fact.ownerDomain(), fact.kind(), fact.factId(),
				fact.factRevision(), fact.occurredAt(), fact.verifiedAt());
		try {
			actionPolicy.validateCompletionEvidence(occurrence.sourceActionType(), evidence);
		}
		catch (IllegalArgumentException exception) {
			throw invalidCompletionEvidence(exception.getMessage());
		}
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		if (occurrence.evidenceEquals(evidence)) {
			return completion(occurrence);
		}
		if (!"SCHEDULED".equals(occurrence.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "OCCURRENCE_NOT_OPEN",
					"Only a scheduled native action occurrence accepts completion evidence");
		}
		var existingOccurrenceId = occurrences.findOccurrenceIdByEvidence(fact.ownerDomain(), fact.kind(),
				fact.factId(), fact.factRevision());
		if (existingOccurrenceId.isPresent() && !existingOccurrenceId.get().equals(occurrenceId)) {
			throw evidenceAlreadyUsed();
		}
		occurrence.completeWithEvidence(evidence, now);
		try {
			occurrence = occurrences.saveAndFlush(occurrence);
		}
		catch (DataIntegrityViolationException exception) {
			if (exception.getMostSpecificCause().getMessage() != null
					&& exception.getMostSpecificCause().getMessage()
							.contains("ux_support_plan_activity_occurrence_evidence_fact")) {
				throw evidenceAlreadyUsed();
			}
			throw exception;
		}
		appendActionOccurrenceEvent(occurrence, correlationId, now);
		return completion(occurrence);
	}

	/**
	 * Authoritative lifecycle boundary for ending one native PlanAction. Ending the
	 * action, its schedules, and its future open occurrences is one transaction so
	 * callers cannot leave a partially ended action behind.
	 */
	@Transactional
	public ActionEndResult endAction(UUID userId, UUID actionId) {
		var planId = actions.findSupportPlanIdByIdAndUserId(actionId, userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "PLAN_ACTION_NOT_FOUND", "PlanAction was not found"));
		var plan = plans.findByIdAndUserIdForUpdate(planId, userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"));
		var action = actions.findByIdAndUserIdForUpdate(actionId, userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "PLAN_ACTION_NOT_FOUND", "PlanAction was not found"));
		if ("DRAFT".equals(action.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "PLAN_ACTION_NOT_ACTIVE",
					"A draft PlanAction cannot be ended");
		}
		if (!List.of("ACTIVE", "PAUSED").contains(plan.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_NOT_MUTABLE",
					"A terminal SupportPlan does not accept action lifecycle changes");
		}
		if (!List.of("ACTIVE", "ENDED").contains(action.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "PLAN_ACTION_STATE_INVALID",
					"PlanAction state does not support ending");
		}

		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		boolean actionChanged = false;
		if ("ACTIVE".equals(action.status())) {
			action.end(now);
			actionChanged = true;
		}
		int endedSchedules = 0;
		for (var schedule : schedules.findBySourceActionIdAndUserIdForUpdate(actionId, userId)) {
			if (!"ENDED".equals(schedule.status())) {
				LocalDate last = now.atZone(zone(schedule.timezone())).toLocalDate().minusDays(1);
				schedule.end(last, now);
				endedSchedules++;
			}
		}
		if (actionChanged || endedSchedules > 0) {
			plan.actionChanged(now);
			plans.saveAndFlush(plan);
		}
		int cancelledOccurrences = occurrences.cancelFutureForEndedAction(actionId, userId, now);
		return new ActionEndResult(action.id(), action.version(), action.status(), action.endedAt(),
				endedSchedules, cancelledOccurrences);
	}

	@Transactional(readOnly = true)
	public OccurrenceView detail(UUID userId, UUID occurrenceId) {
		return view(required(userId, occurrenceId), clock.instant());
	}

	@Transactional
	public OccurrenceView changeState(UUID userId, UUID occurrenceId, long expectedVersion, String desiredState,
			UUID correlationId) {
		if (!List.of("COMPLETED", "SKIPPED").contains(desiredState)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "OCCURRENCE_STATE_INVALID",
					"Occurrence state must be COMPLETED or SKIPPED");
		}
		var occurrence = requiredForEngagement(userId, occurrenceId);
		if (desiredState.equals(occurrence.state())) {
			return view(occurrence, clock.instant());
		}
		version(occurrence, expectedVersion);
		if (!"SCHEDULED".equals(occurrence.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "OCCURRENCE_NOT_OPEN",
					"Only a scheduled or missed occurrence can be completed or skipped");
		}
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		occurrence.replaceEngagement(desiredState, occurrence.hidden(), null, null, null, false, now);
		occurrence = occurrences.saveAndFlush(occurrence);
		appendEngagementEvent(occurrence, "REPLACED", correlationId, now);
		return view(occurrence, now);
	}

	@Transactional
	public OccurrenceView replaceEngagement(UUID userId, UUID occurrenceId, long expectedVersion,
			String desiredState, boolean hidden, String helpfulness, String barrierCode, String reflection,
			boolean summaryReuseApproved, UUID correlationId) {
		String normalizedReflection = reflection == null ? null : reflection.strip();
		validateEngagement(desiredState, helpfulness, barrierCode, normalizedReflection, summaryReuseApproved);
		var occurrence = requiredForEngagement(userId, occurrenceId);
		if (occurrence.engagementEquals(desiredState, hidden, helpfulness, barrierCode,
				normalizedReflection, summaryReuseApproved)) {
			return view(occurrence, clock.instant());
		}
		version(occurrence, expectedVersion);
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		occurrence.replaceEngagement(desiredState, hidden, helpfulness, barrierCode,
				normalizedReflection, summaryReuseApproved, now);
		occurrence = occurrences.saveAndFlush(occurrence);
		appendEngagementEvent(occurrence, "REPLACED", correlationId, now);
		return view(occurrence, now);
	}

	@Transactional
	public OccurrenceView deleteEngagement(UUID userId, UUID occurrenceId, long expectedVersion,
			UUID correlationId) {
		var occurrence = requiredForEngagement(userId, occurrenceId);
		if (occurrence.engagementEquals("SCHEDULED", false, null, null, null, false)) {
			return view(occurrence, clock.instant());
		}
		version(occurrence, expectedVersion);
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		occurrence.deleteEngagement(now);
		occurrence = occurrences.saveAndFlush(occurrence);
		appendEngagementEvent(occurrence, "DELETED", correlationId, now);
		return view(occurrence, now);
	}

	@Transactional
	void pause(UUID planId, Instant now) {
		for (var schedule : schedules.findBySupportPlanIdForUpdate(planId)) {
			schedule.pause(now);
		}
		occurrences.cancelFuture(planId, "PLAN_PAUSED", now);
	}

	@Transactional
	void resume(SupportPlanEntity plan, Instant now) {
		var values = schedules.findBySupportPlanIdForUpdate(plan.id());
		if (values.isEmpty()) {
			activate(plan, now);
			return;
		}
		for (var schedule : values) {
			schedule.resume(now);
		}
		occurrences.restoreFuturePaused(plan.id(), now);
		ZoneId zone = zone(values.get(0).timezone());
		LocalDate start = now.atZone(zone).toLocalDate();
		generate(plan.id(), start, start.plusDays(INITIAL_HORIZON_DAYS - 1), now);
	}

	@Transactional
	void end(UUID planId, String reason, Instant now) {
		var values = schedules.findBySupportPlanIdForUpdate(planId);
		for (var schedule : values) {
			LocalDate last = now.atZone(zone(schedule.timezone())).toLocalDate().minusDays(1);
			schedule.end(last, now);
		}
		occurrences.cancelFutureForTerminal(planId, reason, now);
	}

	private void validateWindow(UUID userId, LocalDate from, LocalDate through) {
		if (from == null || through == null || through.isBefore(from)
				|| ChronoUnit.DAYS.between(from, through) >= MAX_QUERY_DAYS) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "OCCURRENCE_WINDOW_INVALID",
					"Occurrence window must be an inclusive range of at most 31 local days");
		}
		String timezone = profiles.findTimezoneByAccountId(userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Care profile was not found"));
		LocalDate today = clock.instant().atZone(zone(timezone)).toLocalDate();
		if (from.isBefore(today.minusDays(31)) || through.isAfter(today.plusDays(30))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "OCCURRENCE_WINDOW_INVALID",
					"Occurrence window is outside the supported today and upcoming range");
		}
	}

	private void generate(UUID planId, LocalDate from, LocalDate through, Instant now) {
		for (var schedule : schedules.findBySupportPlanIdForUpdate(planId)) {
			if (!"ACTIVE".equals(schedule.status())) {
				continue;
			}
			LocalDate start = from.isBefore(schedule.effectiveFrom()) ? schedule.effectiveFrom() : from;
			LocalDate end = schedule.effectiveUntil() == null || through.isBefore(schedule.effectiveUntil())
					? through : schedule.effectiveUntil();
			for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
				if (!occurs(schedule, date)) {
					continue;
				}
				String key = schedule.id() + ":" + schedule.scheduleVersion() + ":" + date;
				UUID occurrenceId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
				Instant scheduledAt = resolve(date, schedule.localTime(), schedule.timezone());
				if (schedule.sourceActionId() == null) {
					occurrences.insertIfAbsent(occurrenceId, schedule.id(), schedule.supportPlanId(),
							schedule.userId(), schedule.scheduleVersion(), date, schedule.localTime(),
							schedule.timezone(), scheduledAt, schedule.sourcePlanVersion(), schedule.sourceSlotKey(),
							schedule.sourceResourceId(), schedule.sourceContentVersion(), schedule.sourceTitle(), now);
				}
				else {
					occurrences.insertNativeIfAbsent(occurrenceId, schedule.id(), schedule.supportPlanId(),
							schedule.userId(), schedule.scheduleVersion(), date, schedule.localTime(),
							schedule.timezone(), scheduledAt, schedule.sourcePlanVersion(), schedule.sourceActionId(),
							schedule.sourceActionVersion(), schedule.sourceActionType(), schedule.sourceTitle(), now);
				}
			}
		}
	}

	private boolean occurs(SupportPlanActivityScheduleEntity schedule, LocalDate date) {
		return occurs(schedule.recurrenceType(), schedule.recurrenceDayOfWeek(), schedule.effectiveFrom(), date);
	}

	boolean occurs(String recurrenceType, Short recurrenceDayOfWeek, LocalDate effectiveFrom, LocalDate date) {
		return switch (recurrenceType) {
			case "ONCE" -> date.equals(effectiveFrom);
			case "DAILY" -> true;
			case "WEEKLY" -> recurrenceDayOfWeek != null
					&& date.getDayOfWeek().getValue() == recurrenceDayOfWeek;
			default -> throw new IllegalStateException("Unsupported stored recurrence type");
		};
	}

	Instant resolve(LocalDate date, LocalTime time, String timezone) {
		ZoneId zone = zone(timezone);
		LocalDateTime local = LocalDateTime.of(date, time);
		List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
		if (offsets.size() == 1) {
			return ZonedDateTime.ofLocal(local, zone, offsets.get(0)).toInstant();
		}
		if (offsets.size() == 2) {
			return ZonedDateTime.ofLocal(local, zone, offsets.get(0)).toInstant();
		}
		return zone.getRules().getTransition(local).getDateTimeAfter().atZone(zone).toInstant();
	}

	private ZoneId zone(String timezone) {
		try {
			return ZoneId.of(timezone);
		}
		catch (Exception exception) {
			throw new ApiException(HttpStatus.CONFLICT, "PROFILE_TIMEZONE_INVALID",
					"The stored profile timezone is not a valid IANA timezone");
		}
	}

	private SupportPlanActivityOccurrenceEntity required(UUID userId, UUID occurrenceId) {
		var occurrence = occurrences.findByIdAndUserId(occurrenceId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "OCCURRENCE_NOT_FOUND", "SupportPlan occurrence was not found"));
		return requireLegacyOccurrence(occurrence);
	}

	private SupportPlanActivityOccurrenceEntity requiredForEngagement(UUID userId, UUID occurrenceId) {
		var planId = occurrences.findSupportPlanIdByIdAndUserId(occurrenceId, userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "OCCURRENCE_NOT_FOUND",
						"SupportPlan occurrence was not found"));
		var plan = plans.findByIdAndUserIdForUpdate(planId, userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"));
		if (!"ACTIVE".equals(plan.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_NOT_ACTIVE",
					"Only an active current SupportPlan accepts engagement updates");
		}
		var occurrence = occurrences.findByIdAndUserIdForUpdate(occurrenceId, userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "OCCURRENCE_NOT_FOUND",
						"SupportPlan occurrence was not found"));
		if ("CANCELLED".equals(occurrence.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "OCCURRENCE_NOT_OPEN",
					"A cancelled occurrence does not accept engagement updates");
		}
		return requireLegacyOccurrence(occurrence);
	}

	private SupportPlanActivityOccurrenceEntity requireLegacyOccurrence(
			SupportPlanActivityOccurrenceEntity occurrence) {
		if (occurrence.sourceActionId() != null) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED",
					"This endpoint supports resource-slot SupportPlan occurrences only");
		}
		return occurrence;
	}

	private void validateEngagement(String state, String helpfulness, String barrierCode, String reflection,
			boolean summaryReuseApproved) {
		if (!List.of("SCHEDULED", "COMPLETED", "SKIPPED").contains(state)) {
			throw invalidEngagement("Engagement state must be SCHEDULED, COMPLETED, or SKIPPED");
		}
		if (helpfulness != null && !List.of("NOT_HELPFUL", "A_LITTLE_HELPFUL", "HELPFUL", "VERY_HELPFUL")
				.contains(helpfulness)) {
			throw invalidEngagement("Helpfulness value is not supported");
		}
		if (barrierCode != null && !List.of("LOW_ENERGY", "NOT_ENOUGH_TIME", "DIFFICULT_TO_START",
				"NOT_A_GOOD_FIT", "OTHER").contains(barrierCode)) {
			throw invalidEngagement("Barrier code is not supported");
		}
		if (reflection != null && (reflection.isBlank() || reflection.length() > 500)) {
			throw invalidEngagement("Reflection must contain one through 500 characters");
		}
		if (("SCHEDULED".equals(state) && (helpfulness != null || barrierCode != null || reflection != null
				|| summaryReuseApproved)) || ("COMPLETED".equals(state) && barrierCode != null)
				|| ("SKIPPED".equals(state) && helpfulness != null)) {
			throw invalidEngagement("Engagement details do not match the selected state");
		}
	}

	private void version(SupportPlanActivityOccurrenceEntity occurrence, long expectedVersion) {
		if (occurrence.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "OCCURRENCE_VERSION_MISMATCH",
					"Occurrence version does not match If-Match");
		}
	}

	private ApiException invalidEngagement(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "OCCURRENCE_ENGAGEMENT_INVALID", message);
	}

	private ApiException invalidCompletionEvidence(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "COMPLETION_EVIDENCE_INVALID", message);
	}

	private void validateFactAuthority(PlanActionEntity action, AuthoritativeOwnerFact authoritativeFact) {
		if (!action.userId().equals(authoritativeFact.subjectUserId())) {
			throw invalidCompletionEvidence("Completion evidence subject does not own the PlanAction");
		}
		var linkedSource = authoritativeFact.linkedSource();
		boolean matches = action.sourceId() == null
				? linkedSource == null
				: linkedSource != null
						&& Objects.equals(action.sourceOwner(), linkedSource.ownerDomain())
						&& Objects.equals(action.sourceKind(), linkedSource.kind())
						&& Objects.equals(action.sourceId(), linkedSource.sourceId())
						&& Objects.equals(action.sourceRevision(), linkedSource.sourceRevision());
		if (!matches) {
			throw invalidCompletionEvidence("Completion evidence is not linked to the PlanAction source");
		}
	}

	private ApiException evidenceAlreadyUsed() {
		return new ApiException(HttpStatus.CONFLICT, "COMPLETION_EVIDENCE_ALREADY_USED",
				"The authoritative fact already completes another action occurrence");
	}

	private void appendEngagementEvent(SupportPlanActivityOccurrenceEntity occurrence, String changeType,
			UUID correlationId, Instant now) {
		try {
			var payload = new EngagementEventPayload(occurrence.id(), occurrence.supportPlanId(),
					occurrence.userId(), changeType, occurrence.state(), occurrence.hidden(), occurrence.helpfulness(),
					occurrence.barrierCode(), occurrence.summaryReuseApproved(), occurrence.sourcePlanVersion(),
					occurrence.sourceSlotKey(), occurrence.sourceResourceId(),
					Long.toString(occurrence.sourceContentVersion()), occurrence.engagementUpdatedAt(),
					"SELF_REPORTED_WELLBEING_ACTIVITY_NOT_TREATMENT_ADHERENCE");
			occurrences.insertEngagementOutbox(UUID.randomUUID(), occurrence.id(), occurrence.version(),
					correlationId == null ? UUID.randomUUID() : correlationId,
					objectMapper.writeValueAsString(payload), now);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("SupportPlan engagement event could not be serialized", exception);
		}
	}

	private void appendActionOccurrenceEvent(SupportPlanActivityOccurrenceEntity occurrence,
			UUID correlationId, Instant now) {
		try {
			var fact = new OwnerFact(occurrence.evidenceOwner(), occurrence.evidenceKind(),
					occurrence.evidenceId(), occurrence.evidenceRevision(), occurrence.evidenceOccurredAt(),
					occurrence.evidenceVerifiedAt());
			var orchestration = new ActionOrchestrationEvent(occurrence.state(), occurrence.state(),
					occurrence.stateReason(), occurrence.completedAt(),
					"SELF_REPORTED_WELLBEING_ACTIVITY_NOT_TREATMENT_ADHERENCE");
			var payload = new ActionOccurrenceEventPayload(occurrence.supportPlanId(), occurrence.userId(),
					occurrence.sourcePlanVersion(), occurrence.sourceActionId().toString(),
					occurrence.sourceActionType(), occurrence.sourceActionVersion(),
					occurrence.activityScheduleId(), occurrence.scheduleVersion(), occurrence.id(), "NATIVE_V3",
					"EVIDENCE_ATTACHED", orchestration, fact);
			occurrences.insertActionOccurrenceOutbox(UUID.randomUUID(), occurrence.id(), occurrence.version(),
					correlationId == null ? UUID.randomUUID() : correlationId,
					objectMapper.writeValueAsString(payload), now);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("PlanAction occurrence event could not be serialized", exception);
		}
	}

	private ActionOccurrenceCompletion completion(SupportPlanActivityOccurrenceEntity occurrence) {
		return new ActionOccurrenceCompletion(occurrence.id(), occurrence.version(), occurrence.state(),
				occurrence.completedAt(), new OwnerFact(occurrence.evidenceOwner(), occurrence.evidenceKind(),
						occurrence.evidenceId(), occurrence.evidenceRevision(), occurrence.evidenceOccurredAt(),
						occurrence.evidenceVerifiedAt()));
	}

	private OccurrenceView view(SupportPlanActivityOccurrenceEntity occurrence, Instant now) {
		String displayState = "SCHEDULED".equals(occurrence.state()) && occurrence.scheduledAt().isBefore(now)
				? "MISSED" : occurrence.state();
		return new OccurrenceView(occurrence.id(), occurrence.supportPlanId(), occurrence.activityScheduleId(),
				occurrence.scheduleVersion(), occurrence.localDate(), occurrence.localTime(), occurrence.timezone(),
				occurrence.scheduledAt(), occurrence.state(), displayState, occurrence.stateReason(),
				occurrence.version(), new OccurrenceSourceView("RESOURCE", occurrence.sourcePlanVersion(),
						occurrence.sourceSlotKey(), occurrence.sourceResourceId(),
						Long.toString(occurrence.sourceContentVersion()), occurrence.sourceTitle()),
				occurrence.updatedAt(), occurrence.completedAt(), occurrence.skippedAt(), occurrence.cancelledAt(),
				occurrence.hidden(), occurrence.helpfulness(), occurrence.barrierCode(), occurrence.reflection(),
				occurrence.summaryReuseApproved(), occurrence.engagementUpdatedAt(),
				"SELF_REPORTED_WELLBEING_ACTIVITY_NOT_TREATMENT_ADHERENCE");
	}

	public record OccurrenceListView(UUID supportPlanId, String supportPlanStatus, String schedulePolicyVersion,
			LocalDate from, LocalDate through, List<OccurrenceView> occurrences, String interpretationCode) { }
	record V3Window(SupportPlanEntity plan, List<SupportPlanActivityOccurrenceEntity> occurrences) { }
	public record OccurrenceSourceView(String type, long supportPlanVersion, String slotId, UUID resourceId,
			String contentVersion, String title) { }
	public record OccurrenceView(UUID occurrenceId, UUID supportPlanId, UUID scheduleId, int scheduleVersion,
			LocalDate localDate, LocalTime localTime, String timezone, Instant scheduledAt, String state,
			String displayState, String stateReason, long version, OccurrenceSourceView source, Instant updatedAt,
			Instant completedAt, Instant skippedAt, Instant cancelledAt, boolean hidden, String helpfulness,
			String barrierCode, String reflection, boolean summaryReuseApproved, Instant engagementUpdatedAt,
			String interpretationCode) { }
	private record EngagementEventPayload(UUID occurrenceId, UUID supportPlanId, UUID userId, String changeType,
			String state, boolean hidden, String helpfulness, String barrierCode, boolean summaryReuseApproved,
			long sourcePlanVersion, String sourceSlotId, UUID sourceResourceId, String sourceContentVersion,
			Instant engagementUpdatedAt, String interpretationCode) { }
	public record OwnerFact(String ownerDomain, String kind, String factId, String factRevision,
			Instant occurredAt, Instant verifiedAt) { }
	public record LinkedActionSource(String ownerDomain, String kind, String sourceId,
			String sourceRevision) { }
	public record AuthoritativeOwnerFact(UUID subjectUserId, LinkedActionSource linkedSource,
			OwnerFact fact) { }
	public record ActionOccurrenceCompletion(UUID occurrenceId, long version, String state,
			Instant completedAt, OwnerFact ownerFact) { }
	public record ActionEndResult(UUID actionId, long actionVersion, String status, Instant endedAt,
			int endedScheduleCount, int cancelledOccurrenceCount) { }
	private record ActionOrchestrationEvent(String state, String displayState, String stateReason,
			Instant outcomeRecordedAt, String interpretationCode) { }
	private record ActionOccurrenceEventPayload(UUID supportPlanId, UUID userId, long planVersion,
			String actionId, String actionType, long actionVersion, UUID scheduleId, int scheduleVersion,
			UUID occurrenceId, String representationMode, String changeType,
			ActionOrchestrationEvent orchestration, OwnerFact ownerFact) { }
}
