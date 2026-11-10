package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.mentalbridge.care.shared.ApiException;

class SupportPlanActivityOccurrenceServiceTests {

	private final SupportPlanActivityOccurrenceService service = new SupportPlanActivityOccurrenceService(
			null, null, null, null, null, null, null, Clock.systemUTC(),
			new com.fasterxml.jackson.databind.ObjectMapper());

	@Test
	void shiftsAClockGapToTheFirstValidLocalInstant() {
		assertThat(service.resolve(LocalDate.parse("2026-03-08"), LocalTime.parse("02:30"),
				"America/New_York")).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
	}

	@Test
	void selectsTheEarlierOffsetDuringAClockOverlap() {
		assertThat(service.resolve(LocalDate.parse("2026-11-01"), LocalTime.parse("01:30"),
				"America/New_York")).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
	}

	@Test
	void generatesAOneTimeActionOnlyOnItsEffectiveDate() {
		var effectiveFrom = LocalDate.parse("2026-10-11");

		assertThat(service.occurs("ONCE", null, effectiveFrom, effectiveFrom)).isTrue();
		assertThat(service.occurs("ONCE", null, effectiveFrom, effectiveFrom.plusDays(1))).isFalse();
	}

	@ParameterizedTest
	@CsvSource({"2,ACTIVE", "3,PAUSED"})
	void readsCurrentOwnerOutcomesWithoutModelGuardOrGeneration(int modelVersion, String status) {
		UUID user = UUID.randomUUID();
		UUID planId = UUID.randomUUID();
		Instant from = Instant.parse("2026-08-01T00:00:00Z");
		Instant to = Instant.parse("2026-11-01T00:00:00Z");
		var plans = mock(SupportPlanRepository.class);
		var occurrences = mock(SupportPlanActivityOccurrenceRepository.class);
		var plan = mock(SupportPlanEntity.class);
		var outcome = mock(SupportPlanActivityOccurrenceRepository.OutcomeTimestamp.class);
		when(plan.id()).thenReturn(planId);
		when(plan.modelVersion()).thenReturn(modelVersion);
		when(plans.findByUserIdAndStatusIn(user, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.of(plan));
		when(outcome.getState()).thenReturn("SKIPPED");
		when(outcome.getSkippedAt()).thenReturn(Instant.parse("2026-10-01T01:00:00Z"));
		when(occurrences.findOutcomes(planId, user, from, to)).thenReturn(List.of(outcome));
		var reader = new SupportPlanActivityOccurrenceService(plans, null, null, null, occurrences,
				null, null, Clock.systemUTC(), new com.fasterxml.jackson.databind.ObjectMapper());

		assertThat(reader.outcomes(user, from, to)).containsExactly(
				new SupportPlanActivityOccurrenceService.Outcome("SKIPPED", Instant.parse("2026-10-01T01:00:00Z")));
		verify(occurrences).findOutcomes(planId, user, from, to);
		verifyNoMoreInteractions(occurrences);
	}

	@Test
	void missingCurrentPlanDoesNotReadAnyOccurrence() {
		var plans = mock(SupportPlanRepository.class);
		var occurrences = mock(SupportPlanActivityOccurrenceRepository.class);
		UUID user = UUID.randomUUID();
		var reader = new SupportPlanActivityOccurrenceService(plans, null, null, null, occurrences,
				null, null, Clock.systemUTC(), new com.fasterxml.jackson.databind.ObjectMapper());

		assertThatThrownBy(() -> reader.outcomes(user, Instant.EPOCH, Instant.EPOCH.plusSeconds(1)))
				.isInstanceOf(ApiException.class);
		verifyNoMoreInteractions(occurrences);
	}
}
