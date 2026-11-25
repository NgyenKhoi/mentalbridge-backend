package com.mentalbridge.identity.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class ReportCadenceTests {
	@Test
	void nextOccurrenceIsStrictlyFutureInItsTimezone() {
		assertThat(ReportCadence.DAILY.nextAfter(Instant.parse("2026-10-09T01:00:00Z"), "Asia/Ho_Chi_Minh", LocalTime.of(8, 0)))
				.isEqualTo(Instant.parse("2026-10-10T01:00:00Z"));
		assertThat(ReportCadence.WEEKLY.nextAfter(Instant.parse("2026-10-09T01:00:00Z"), "Asia/Ho_Chi_Minh", LocalTime.of(8, 0)))
				.isEqualTo(Instant.parse("2026-10-12T01:00:00Z"));
		assertThat(ReportCadence.MONTHLY.nextAfter(Instant.parse("2026-12-31T01:00:00Z"), "Asia/Ho_Chi_Minh", LocalTime.of(8, 0)))
				.isEqualTo(Instant.parse("2027-01-01T01:00:00Z"));
	}

	@Test
	void dstGapShiftsForwardAndOverlapRunsOnlyAtEarlierOffset() {
		assertThat(ReportCadence.DAILY.nextAfter(Instant.parse("2026-03-08T05:00:00Z"), "America/New_York", LocalTime.of(2, 30)))
				.isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
		assertThat(ReportCadence.DAILY.nextAfter(Instant.parse("2026-11-01T05:30:00Z"), "America/New_York", LocalTime.of(1, 30)))
				.isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
	}
}
