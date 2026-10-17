package com.mentalbridge.identity.reporting;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

public enum ReportCadence {

	DAILY, WEEKLY, MONTHLY;

	Instant nextAfter(Instant after, String timezone, LocalTime time) {
		var zone = ZoneId.of(timezone);
		var date = after.atZone(zone).toLocalDate();
		if (this == WEEKLY) date = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
		if (this == MONTHLY) date = date.withDayOfMonth(1);
		var candidate = date.atTime(time).atZone(zone).toInstant();
		if (!candidate.isAfter(after)) {
			date = switch (this) {
				case DAILY -> date.plusDays(1);
				case WEEKLY -> date.plusWeeks(1);
				case MONTHLY -> date.plusMonths(1);
			};
			candidate = date.atTime(time).atZone(zone).toInstant();
		}
		return candidate;
	}

}
