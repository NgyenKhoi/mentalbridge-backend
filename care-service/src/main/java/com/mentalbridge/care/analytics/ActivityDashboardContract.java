package com.mentalbridge.care.analytics;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ActivityDashboardContract {

	private ActivityDashboardContract() { }

	public record Summary(int totalActivities, int activeDays, int assessmentSubmissions, int journalEntries,
			int journalActiveDays, int emotionCheckIns, int emotionActiveDays, int supportCompleted,
			int supportSkipped, int appointmentEvents, int currentEmotionStreak, String latestAssessmentInstrument,
			Instant latestAssessmentSubmittedAt) { }

	public record DailyBucket(LocalDate localDate, int assessments, int journals, int emotions,
			Integer emotionLevel, int supportCompleted, int supportSkipped, int appointments, int total) { }

	public record Bounds(int windowDays, int assessmentLimit, int journalLimit, int emotionLimit,
			int appointmentLimit) { }

	public record Response(LocalDate asOfLocalDate, LocalDate startLocalDate, String timezone, Summary summary,
			List<DailyBucket> daily, Map<String, String> sources, boolean partial, Bounds bounded) { }
}
