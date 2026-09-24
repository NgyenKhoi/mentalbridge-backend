package com.mentalbridge.care.reassessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class JournalLongitudinalContract {

	private JournalLongitudinalContract() { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Evidence(UUID analysisId, Period previousPeriod, Period currentPeriod,
			List<SourceRevision> sourceJournalRevisions, List<String> contextSignals,
			List<String> emotionIndicators, List<String> recurringThemes,
			List<Change> changesComparedWithPreviousPeriod, List<String> preferences,
			List<String> barriers, List<String> helpfulPatterns, Coverage dataCoverage,
			String provider, String model, String promptVersion, Integer schemaVersion, Instant createdAt) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Period(Instant startAt, Instant endAt) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SourceRevision(UUID journalId, Integer journalRevision, String period) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Change(String signal, String direction) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Coverage(Integer previousPeriodJournalEntryCount, Integer currentPeriodJournalEntryCount,
			Boolean sufficientForComparison) { }
}
