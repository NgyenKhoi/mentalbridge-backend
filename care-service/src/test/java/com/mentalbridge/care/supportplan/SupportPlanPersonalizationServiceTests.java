package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.profile.UserProfileEntity;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.ActivityMode;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.CommonBarrier;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.DesiredPace;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationCommand;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PreferredDaypart;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PreferredFocus;

class SupportPlanPersonalizationServiceTests {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

	private final SupportPlanPersonalizationRepository personalizations = mock(SupportPlanPersonalizationRepository.class);
	private final SupportPlanPersonalizationActivityModeRepository activityModes =
			mock(SupportPlanPersonalizationActivityModeRepository.class);
	private final SupportPlanPersonalizationBarrierRepository barriers =
			mock(SupportPlanPersonalizationBarrierRepository.class);
	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final SupportPlanPersonalizationService service = new SupportPlanPersonalizationService(
			personalizations, activityModes, barriers, plans, profiles, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void returnsDeterministicNotProvidedFallbackForAnExistingUser() {
		when(profiles.existsById(USER_ID)).thenReturn(true);
		when(personalizations.findFirstByUserIdOrderByRevisionDesc(USER_ID)).thenReturn(Optional.empty());
		when(plans.findByUserIdAndStatusIn(USER_ID, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.empty());

		var result = service.current(USER_ID);

		assertThat(result.revision()).isZero();
		assertThat(result.status()).isEqualTo("NOT_PROVIDED");
		assertThat(result.preferredActivityModes()).isEmpty();
		assertThat(result.planReview().required()).isFalse();
	}

	@Test
	void storesACompleteBoundedRevisionAndMarksAnExistingPlanForExplicitReview() {
		var profile = mock(UserProfileEntity.class);
		var plan = legacyPlan();
		plan.activate(NOW.minusSeconds(30));
		when(profiles.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(profile));
		when(personalizations.findFirstByUserIdOrderByRevisionDesc(USER_ID)).thenReturn(Optional.empty());
		when(personalizations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(plans.findByUserIdAndStatusIn(USER_ID, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.of(plan));
		var command = new PersonalizationCommand(PreferredFocus.SLEEP, DesiredPace.GENTLE, 10,
				PreferredDaypart.EVENING, List.of(ActivityMode.READING, ActivityMode.JOURNALING),
				List.of(CommonBarrier.LOW_ENERGY));

		var result = service.replace(USER_ID, 0, command);

		assertThat(result.revision()).isEqualTo(1);
		assertThat(result.status()).isEqualTo("PROVIDED");
		assertThat(result.preferredFocus()).isEqualTo(PreferredFocus.SLEEP);
		assertThat(result.planReview().required()).isTrue();
		assertThat(result.planReview().supportPlanId()).isEqualTo(plan.id());
		assertThat(result.planReview().reasonCode()).isEqualTo("PREFERENCES_CHANGED_AFTER_PLAN_ACTIVATION");
		verify(activityModes).flush();
		verify(barriers).flush();
	}

	@Test
	void explicitSkipKeepsAnUnpersonalizedActivePlanOnTheDeterministicFallback() {
		var profile = mock(UserProfileEntity.class);
		var plan = legacyPlan();
		plan.activate(NOW.minusSeconds(30));
		when(profiles.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(profile));
		when(personalizations.findFirstByUserIdOrderByRevisionDesc(USER_ID)).thenReturn(Optional.empty());
		when(personalizations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(plans.findByUserIdAndStatusIn(USER_ID, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.of(plan));

		var result = service.replace(USER_ID, 0,
				new PersonalizationCommand(null, null, null, null, List.of(), List.of()));

		assertThat(result.status()).isEqualTo("SKIPPED");
		assertThat(result.planReview().required()).isFalse();
		assertThat(result.planReview().supportPlanId()).isEqualTo(plan.id());
	}

	@Test
	void marksADraftPinnedToAnOlderPreferenceRevisionForExplicitRegeneration() {
		var profile = mock(UserProfileEntity.class);
		var previousCommand = new PersonalizationCommand(PreferredFocus.SLEEP, null, null,
				PreferredDaypart.EVENING, List.of(), List.of());
		var previous = new SupportPlanPersonalizationEntity(UUID.randomUUID(), USER_ID, 1,
				previousCommand, NOW.minusSeconds(60));
		var plan = legacyPlan();
		plan.snapshotPersonalization(new SupportPlanPersonalizationService.PersonalizationSnapshot(
				previous.id(), USER_ID, SupportPlanPersonalizationService.SCHEMA_VERSION, 1,
				PreferredFocus.SLEEP, null, null, PreferredDaypart.EVENING,
				List.of(), List.of(), NOW.minusSeconds(60)));
		when(profiles.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(profile));
		when(personalizations.findFirstByUserIdOrderByRevisionDesc(USER_ID)).thenReturn(Optional.of(previous));
		when(personalizations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(plans.findByUserIdAndStatus(USER_ID, "DRAFT")).thenReturn(Optional.of(plan));

		var result = service.replace(USER_ID, 1,
				new PersonalizationCommand(PreferredFocus.ANXIETY, null, null,
						PreferredDaypart.MORNING, List.of(), List.of()));

		assertThat(result.planReview().required()).isTrue();
		assertThat(result.planReview().supportPlanId()).isEqualTo(plan.id());
		assertThat(result.planReview().reasonCode())
				.isEqualTo("PREFERENCES_CHANGED_AFTER_DRAFT_CREATION");
	}

	@Test
	void identicalReplacementIsANoOpAtTheCurrentRevision() {
		var profile = mock(UserProfileEntity.class);
		var command = new PersonalizationCommand(null, null, null, null, List.of(), List.of());
		var current = new SupportPlanPersonalizationEntity(UUID.randomUUID(), USER_ID, 2, command, NOW);
		when(profiles.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(profile));
		when(personalizations.findFirstByUserIdOrderByRevisionDesc(USER_ID)).thenReturn(Optional.of(current));
		when(activityModes.findByPersonalizationIdOrderByOrdinal(current.id())).thenReturn(List.of());
		when(barriers.findByPersonalizationIdOrderByOrdinal(current.id())).thenReturn(List.of());
		when(plans.findByUserIdAndStatusIn(USER_ID, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.empty());

		var result = service.replace(USER_ID, 2, command);

		assertThat(result.revision()).isEqualTo(2);
		assertThat(result.status()).isEqualTo("SKIPPED");
		verify(personalizations, never()).saveAndFlush(any());
	}

	@Test
	void rejectsStaleAndInvalidReplacementsWithoutCreatingARevision() {
		var profile = mock(UserProfileEntity.class);
		var current = new SupportPlanPersonalizationEntity(UUID.randomUUID(), USER_ID, 2,
				new PersonalizationCommand(null, null, null, null, List.of(), List.of()), NOW);
		when(profiles.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(profile));
		when(personalizations.findFirstByUserIdOrderByRevisionDesc(USER_ID)).thenReturn(Optional.of(current));

		assertThatThrownBy(() -> service.replace(USER_ID, 1,
				new PersonalizationCommand(null, null, null, null, List.of(), List.of())))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code())
						.isEqualTo("SUPPORT_PLAN_PERSONALIZATION_VERSION_MISMATCH"));
		assertThatThrownBy(() -> service.replace(USER_ID, 2,
				new PersonalizationCommand(null, null, 12, null, List.of(), List.of())))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code())
						.isEqualTo("VALIDATION_FAILED"));
		verify(personalizations, never()).saveAndFlush(any());
	}

	private SupportPlanEntity legacyPlan() {
		return new SupportPlanEntity(UUID.randomUUID(), USER_ID, UUID.randomUUID(),
				"mb-support-routing-capstone-v2", NOW.minusSeconds(60), "content-eligibility-v1", NOW,
				"PLUS", "DEMO", "service-entitlement-v1", 1, NOW, "Plan rationale",
				"NEGATIVE_SAFETY_SCREEN", "NO_SAFETY_SIGNAL", "safety-v1",
				"STANDARD_SAFETY_REMINDER", "Safety guidance", 1, NOW.minusSeconds(60));
	}
}
