package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SupportPlanReminderEligibilityServiceTests {

	private static final Instant AS_OF = Instant.parse("2026-11-15T13:00:00Z");
	private static final LocalDate TODAY = LocalDate.parse("2026-11-15");
	private static final String ZONE = "Asia/Ho_Chi_Minh";

	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final SupportPlanActivityOccurrenceService activities = mock(SupportPlanActivityOccurrenceService.class);
	private final SupportPlanActivityScheduleRepository schedules = mock(SupportPlanActivityScheduleRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final SupportPlanReminderEligibilityService service = new SupportPlanReminderEligibilityService(plans,
			activities, schedules, actions);
	private final UUID userId = UUID.randomUUID();

	@Test
	void noPlan_false() {
		when(plans.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.empty());

		assertThat(service.eligible(userId, AS_OF)).isFalse();
		verify(activities, never()).loadV3Window(any(), any(), any());
	}

	@Test
	void pausedPlan_false() {
		plan("PAUSED");

		assertThat(service.eligible(userId, AS_OF)).isFalse();
		verify(activities, never()).loadV3Window(any(), any(), any());
	}

	@Test
	void scheduledToday_reminderOn_true() {
		var plan = plan("ACTIVE");
		window(plan, occurrence("SCHEDULED", TODAY, ZONE, schedule("ACTIVE", true), action("ACTIVE")));

		assertThat(service.eligible(userId, AS_OF)).isTrue();
		verify(activities).loadV3Window(userId, TODAY.minusDays(1), TODAY.plusDays(1));
	}

	@Test
	void reminderDisabled_false() {
		var plan = plan("ACTIVE");
		window(plan, occurrence("SCHEDULED", TODAY, ZONE, schedule("ACTIVE", false), action("ACTIVE")));

		assertThat(service.eligible(userId, AS_OF)).isFalse();
	}

	@Test
	void completedOrSkipped_false() {
		var plan = plan("ACTIVE");
		window(plan, occurrence("COMPLETED", TODAY, ZONE, schedule("ACTIVE", true), action("ACTIVE")),
				occurrence("SKIPPED", TODAY, ZONE, schedule("ACTIVE", true), action("ACTIVE")),
				occurrence("CANCELLED", TODAY, ZONE, schedule("ACTIVE", true), action("ACTIVE")));

		assertThat(service.eligible(userId, AS_OF)).isFalse();
	}

	@Test
	void scheduleEnded_false() {
		var plan = plan("ACTIVE");
		window(plan, occurrence("SCHEDULED", TODAY, ZONE, schedule("ENDED", true), action("ACTIVE")));

		assertThat(service.eligible(userId, AS_OF)).isFalse();
	}

	@Test
	void actionEnded_false() {
		var plan = plan("ACTIVE");
		window(plan, occurrence("SCHEDULED", TODAY, ZONE, schedule("ACTIVE", true), action("ENDED")));

		assertThat(service.eligible(userId, AS_OF)).isFalse();
	}

	@Test
	void v2Plan_scheduledToday_true() {
		var plan = plan("ACTIVE");
		window(plan, occurrence("SCHEDULED", TODAY, ZONE, schedule("ACTIVE", true), null));

		assertThat(service.eligible(userId, AS_OF)).isTrue();
		verify(actions, never()).findById(any());
	}

	@Test
	void timezoneEdge_usesOccurrenceZone() {
		Instant lateUtc = Instant.parse("2026-11-15T17:30:00Z");
		var plan = plan("ACTIVE");
		window(plan, occurrence("SCHEDULED", LocalDate.parse("2026-11-16"), ZONE, schedule("ACTIVE", true),
				action("ACTIVE")));
		assertThat(service.eligible(userId, lateUtc)).isTrue();

		window(plan, occurrence("SCHEDULED", LocalDate.parse("2026-11-15"), ZONE, schedule("ACTIVE", true),
				action("ACTIVE")));
		assertThat(service.eligible(userId, lateUtc)).isFalse();
	}

	private SupportPlanEntity plan(String status) {
		var plan = mock(SupportPlanEntity.class);
		when(plan.status()).thenReturn(status);
		when(plans.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.of(plan));
		return plan;
	}

	private void window(SupportPlanEntity plan, SupportPlanActivityOccurrenceEntity... values) {
		when(activities.loadV3Window(any(), any(), any()))
				.thenReturn(new SupportPlanActivityOccurrenceService.V3Window(plan, List.of(values)));
	}

	private SupportPlanActivityScheduleEntity schedule(String status, boolean reminderEnabled) {
		var schedule = mock(SupportPlanActivityScheduleEntity.class);
		UUID id = UUID.randomUUID();
		when(schedule.id()).thenReturn(id);
		when(schedule.status()).thenReturn(status);
		when(schedule.reminderEnabled()).thenReturn(reminderEnabled);
		when(schedules.findById(id)).thenReturn(Optional.of(schedule));
		return schedule;
	}

	private PlanActionEntity action(String status) {
		var action = mock(PlanActionEntity.class);
		UUID id = UUID.randomUUID();
		when(action.id()).thenReturn(id);
		when(action.status()).thenReturn(status);
		when(actions.findById(id)).thenReturn(Optional.of(action));
		return action;
	}

	private SupportPlanActivityOccurrenceEntity occurrence(String state, LocalDate localDate, String timezone,
			SupportPlanActivityScheduleEntity schedule, PlanActionEntity action) {
		UUID scheduleId = schedule.id();
		UUID actionId = action == null ? null : action.id();
		var occurrence = mock(SupportPlanActivityOccurrenceEntity.class);
		when(occurrence.state()).thenReturn(state);
		when(occurrence.localDate()).thenReturn(localDate);
		when(occurrence.timezone()).thenReturn(timezone);
		when(occurrence.activityScheduleId()).thenReturn(scheduleId);
		when(occurrence.sourceActionId()).thenReturn(actionId);
		return occurrence;
	}
}
