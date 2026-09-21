package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class SupportPlanActivityOccurrenceServiceTests {

	private final SupportPlanActivityOccurrenceService service = new SupportPlanActivityOccurrenceService(
			null, null, null, null, null, Clock.systemUTC());

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
}
