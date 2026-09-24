package com.mentalbridge.care.reassessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mentalbridge.care.assessment.ScreeningLevel;

public record ReassessmentSummaryView(
		UUID summaryId,
		String summaryVersion,
		Instant composedAt,
		Period previousPeriod,
		Period currentPeriod,
		ScreeningDimension screening,
		JournalDimension journalContext,
		EngagementDimension supportPlanEngagement,
		ReflectionDimension userReflection,
		String disclaimerCode) {

	public record Period(Instant startAt, Instant endAt) { }

	public record ScreeningDimension(String state, List<ScreeningTrend> trends) { }

	public record ScreeningTrend(String instrument, String state, String scoringVersion,
			ScreeningPoint previous, ScreeningPoint current, Integer rawDelta, String direction) { }

	public record ScreeningPoint(UUID assessmentId, String questionnaireVersion, Instant submittedAt,
			int totalScore, ScreeningLevel screeningLevel) { }

	public record JournalDimension(String state, String unavailableReason, UUID analysisId,
			List<JournalSourceRevision> sourceJournalRevisions, List<String> contextSignals,
			List<String> emotionIndicators, List<String> recurringThemes,
			List<JournalChange> changesComparedWithPreviousPeriod, List<String> preferences,
			List<String> barriers, List<String> helpfulPatterns, JournalCoverage dataCoverage,
			JournalProvenance provenance) { }

	public record JournalSourceRevision(UUID journalId, int journalRevision, String period) { }

	public record JournalChange(String signal, String direction) { }

	public record JournalCoverage(int previousPeriodJournalEntryCount, int currentPeriodJournalEntryCount,
			boolean sufficientForComparison) { }

	public record JournalProvenance(String provider, String model, String promptVersion, int schemaVersion,
			Instant createdAt) { }

	public record EngagementDimension(String state, EngagementPeriod previousPeriod,
			EngagementPeriod currentPeriod, List<EngagementSource> sources) { }

	public record EngagementPeriod(int completedCount, int skippedCount) { }

	public record EngagementSource(UUID occurrenceId, UUID supportPlanId, long sourcePlanVersion,
			String sourceSlotId, UUID sourceResourceId, long sourceContentVersion, Instant scheduledAt,
			String period, String state, String barrierCode) { }

	public record ReflectionDimension(String state, List<ReflectionSource> sources) { }

	public record ReflectionSource(UUID occurrenceId, String period, String helpfulness, String reflection,
			Instant engagementUpdatedAt) { }
}
