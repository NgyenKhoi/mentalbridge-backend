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
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;

@Service
public class SupportPlanActivityOccurrenceService {

	static final String SCHEDULE_POLICY_VERSION = "support-plan-activity-schedule-v1";
	private static final int INITIAL_HORIZON_DAYS = 14;
	private static final int MAX_QUERY_DAYS = 31;
	private static final List<LocalTime> DEFAULT_TIMES = List.of(LocalTime.of(8, 0), LocalTime.of(10, 0),
			LocalTime.of(14, 0), LocalTime.of(18, 0), LocalTime.of(20, 0));

	private final SupportPlanRepository plans;
	private final SupportPlanSlotRepository slots;
	private final SupportPlanActivityScheduleRepository schedules;
	private final SupportPlanActivityOccurrenceRepository occurrences;
	private final UserProfileRepository profiles;
	private final Clock clock;
	private final ObjectMapper objectMapper;

	public SupportPlanActivityOccurrenceService(SupportPlanRepository plans, SupportPlanSlotRepository slots,
			SupportPlanActivityScheduleRepository schedules,
			SupportPlanActivityOccurrenceRepository occurrences, UserProfileRepository profiles, Clock clock,
			ObjectMapper objectMapper) {
		this.plans = plans;
		this.slots = slots;
		this.schedules = schedules;
		this.occurrences = occurrences;
		this.profiles = profiles;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	@Transactional
	void activate(SupportPlanEntity plan, Instant now) {
		String timezone = profiles.findTimezoneByAccountId(plan.userId()).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Care profile was not found"));
		ZoneId zone = zone(timezone);
		LocalDate start = now.atZone(zone).toLocalDate();
		var selectedSlots = slots.findBySupportPlanIdOrderByOrdinal(plan.id()).stream()
				.filter(slot -> slot.selectedResource() != null).toList();
		if (schedules.findBySupportPlanIdOrderByOrdinal(plan.id()).isEmpty()) {
			for (SupportPlanSlotEntity slot : selectedSlots) {
				var resource = slot.selectedResource();
				boolean daily = List.of("BREATHING", "MEDITATION", "JOURNALING")
						.contains(resource.category());
				var schedule = new SupportPlanActivityScheduleEntity(UUID.randomUUID(), plan.id(), plan.userId(),
						slot.ordinal(), plan.version(), slot, daily ? "DAILY" : "WEEKLY",
						daily ? null : (short) start.getDayOfWeek().getValue(),
						DEFAULT_TIMES.get(slot.ordinal() - 1), timezone, start, now);
				schedules.save(schedule);
			}
			schedules.flush();
		}
		generate(plan.id(), start, start.plusDays(INITIAL_HORIZON_DAYS - 1), now);
	}

	@Transactional
	public OccurrenceListView list(UUID userId, LocalDate from, LocalDate through) {
		var plan = plans.findByUserIdAndStatusInForUpdate(userId, List.of("ACTIVE", "PAUSED")).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_CURRENT_NOT_FOUND",
						"Current SupportPlan was not found"));
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
				occurrences.insertIfAbsent(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
						schedule.id(), schedule.supportPlanId(), schedule.userId(), schedule.scheduleVersion(), date,
						schedule.localTime(), schedule.timezone(), resolve(date, schedule.localTime(), schedule.timezone()),
						schedule.sourcePlanVersion(), schedule.sourceSlotKey(), schedule.sourceResourceId(),
						schedule.sourceContentVersion(), schedule.sourceTitle(), now);
			}
		}
	}

	private boolean occurs(SupportPlanActivityScheduleEntity schedule, LocalDate date) {
		return "DAILY".equals(schedule.recurrenceType())
				|| date.getDayOfWeek().getValue() == schedule.recurrenceDayOfWeek();
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
		return occurrences.findByIdAndUserId(occurrenceId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "OCCURRENCE_NOT_FOUND", "SupportPlan occurrence was not found"));
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
}
