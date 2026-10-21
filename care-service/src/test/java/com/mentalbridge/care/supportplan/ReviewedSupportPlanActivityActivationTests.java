package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.PlanActionEntity.Presentation;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationSnapshot;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PreferredFocus;

class ReviewedSupportPlanActivityActivationTests {
	private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
	private final UUID userId = UUID.randomUUID();
	private final UUID planId = UUID.randomUUID();
	private final SupportPlanEntity plan = mock(SupportPlanEntity.class);
	private final SupportPlanSlotRepository slots = mock(SupportPlanSlotRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final SupportPlanActivityScheduleRepository schedules = mock(SupportPlanActivityScheduleRepository.class);
	private final SupportPlanActivityOccurrenceRepository occurrences = mock(SupportPlanActivityOccurrenceRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final SupportPlanPersonalizationService preferences = mock(SupportPlanPersonalizationService.class);
	private final PersonalizationSnapshot historical = preference(1, PreferredFocus.MOOD);
	private final PersonalizationSnapshot latest = preference(2, PreferredFocus.ANXIETY);
	private SupportPlanActivityOccurrenceService service;

	@BeforeEach
	void setup() {
		service = new SupportPlanActivityOccurrenceService(mock(SupportPlanRepository.class), slots, actions,
				schedules, occurrences, profiles, preferences, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
		when(plan.id()).thenReturn(planId);
		when(plan.userId()).thenReturn(userId);
		when(plan.version()).thenReturn(7L);
		when(plan.status()).thenReturn("ACTIVE");
		when(plan.personalizationId()).thenReturn(historical.personalizationId());
		when(plan.personalizationRevision()).thenReturn(1L);
		when(preferences.currentSnapshot(userId)).thenReturn(Optional.of(latest));
		when(preferences.snapshot(historical.personalizationId(), userId, 1)).thenReturn(Optional.of(historical));
		when(profiles.findTimezoneByAccountId(userId)).thenReturn(Optional.of("Asia/Ho_Chi_Minh"));
	}

	@Test
	void ordinaryActivationStillRejectsChangedPreferencesBeforeScheduleMutation() {
		assertThatThrownBy(() -> service.activateUserDraft(plan, NOW)).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_PERSONALIZATION_REVIEW_REQUIRED"));
		verifyNoInteractions(slots, actions, schedules, occurrences);
	}

	@Test
	void reviewedActivationKeepsHistoricalCadenceAndGeneratesBoundedOccurrences() {
		var action = new PlanActionEntity(UUID.randomUUID(), planId, UUID.randomUUID(), userId, 1, "RESOURCE", "SUPPORT", null,
				new SourceReference("CONTENT", "RESOURCE_VERSION", UUID.randomUUID().toString(), "1", "resource-policy-v1",
						UUID.randomUUID(), "resource-policy-v1", NOW),
				new Provenance("SYSTEM_POLICY", "anxiety-maintenance", SupportPlanService.SELECTION_POLICY_VERSION, NOW),
				new Presentation("Support activity", null, null, null), NOW);
		action.activate(NOW);
		var time = LocalTime.of(20, 35);
		var schedule = SupportPlanActivityScheduleEntity.revisionForAction(UUID.randomUUID(), plan, action, 1,
				SupportPlanActivityOccurrenceService.SCHEDULE_POLICY_VERSION_V2, "WEEKLY", (short) 18, "EVENING", 15,
				time, "Asia/Ho_Chi_Minh", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 28), true, "DRAFT", NOW);
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(planId, userId)).thenReturn(List.of(action));
		when(schedules.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of(schedule));
		when(schedules.findBySupportPlanIdForUpdate(planId)).thenReturn(List.of(schedule));

		service.activateReviewedDraft(plan, NOW);

		assertThat(schedule.status()).isEqualTo("ACTIVE");
		assertThat(schedule.recurrenceDaysMask()).isEqualTo((short) 18);
		assertThat(schedule.localTime()).isEqualTo(time);
		assertThat(schedule.estimatedDurationMinutes()).isEqualTo((short) 15);
		verify(preferences).snapshot(historical.personalizationId(), userId, 1);
		verify(preferences, never()).currentSnapshot(any());
		verify(occurrences, times(2)).insertNativeIfAbsent(any(), eq(schedule.id()), eq(planId), eq(userId), eq(1),
				any(LocalDate.class), eq(time), eq("Asia/Ho_Chi_Minh"), any(), eq(7L), eq(action.id()),
				eq(action.version()), eq("RESOURCE"), eq("Support activity"), eq(NOW));
	}

	@Test
	void reviewedActivationStillRequiresHistoricalPreferenceProvenance() {
		when(preferences.snapshot(historical.personalizationId(), userId, 1)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.activateReviewedDraft(plan, NOW)).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_PERSONALIZATION_INVALID"));
		verifyNoInteractions(slots, actions, schedules, occurrences);
	}

	private PersonalizationSnapshot preference(long revision, PreferredFocus focus) {
		return new PersonalizationSnapshot(UUID.randomUUID(), userId, "support-plan-personalization-v1", revision,
				focus, null, 15, null, List.of(), List.of(), NOW.minusSeconds(60));
	}
}
