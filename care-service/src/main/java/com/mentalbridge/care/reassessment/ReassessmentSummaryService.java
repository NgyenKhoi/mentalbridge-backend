package com.mentalbridge.care.reassessment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.reassessment.JournalLongitudinalClient.Projection;
import com.mentalbridge.care.reassessment.ReassessmentEvidenceRepository.AssessmentEvidence;
import com.mentalbridge.care.reassessment.ReassessmentEvidenceRepository.EngagementEvidence;
import com.mentalbridge.care.reassessment.ReassessmentSummaryStore.StoredSummary;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.EngagementDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.EngagementPeriod;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.EngagementSource;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.JournalChange;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.JournalCoverage;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.JournalDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.JournalProvenance;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.JournalSourceRevision;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ReflectionDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ReflectionSource;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ScreeningDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ScreeningPoint;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ScreeningTrend;
import com.mentalbridge.care.shared.ApiException;

@Service
public class ReassessmentSummaryService {

	static final String SUMMARY_VERSION = "reassessment-summary-v1";
	private static final String DISCLAIMER = "FOUR_DIMENSIONS_NOT_COMBINED";
	private static final Duration MINIMUM_PERIOD = Duration.ofDays(7);
	private static final Duration MAXIMUM_PERIOD = Duration.ofDays(31);

	private final ReassessmentEvidenceRepository evidence;
	private final JournalLongitudinalClient journal;
	private final ReassessmentSummaryStore summaries;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public ReassessmentSummaryService(ReassessmentEvidenceRepository evidence, JournalLongitudinalClient journal,
			ReassessmentSummaryStore summaries, ObjectMapper objectMapper, Clock clock) {
		this.evidence = evidence;
		this.journal = journal;
		this.summaries = summaries;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public ReassessmentSummaryView compose(UUID userId, String bearerToken, String idempotencyKey,
			UUID correlationId, ComposeCommand command) {
		validatePeriods(command.previousPeriod(), command.currentPeriod());
		String requestHash = hash(command);
		var replay = summaries.findByRequest(userId, idempotencyKey);
		if (replay.isPresent()) return replay(replay.get(), requestHash);

		var phq9 = screeningTrend(userId, command.phq9AssessmentId(), "PHQ9");
		var gad7 = screeningTrend(userId, command.gad7AssessmentId(), "GAD7");
		var screening = new ScreeningDimension(
				"AVAILABLE".equals(phq9.state()) && "AVAILABLE".equals(gad7.state())
						? "AVAILABLE" : "INSUFFICIENT_DATA",
				List.of(phq9, gad7));
		Projection projection = journal.read(userId, command.journalAnalysisId(), command.previousPeriod(),
				command.currentPeriod(), bearerToken, correlationId);
		var engagementEvidence = evidence.findReusableEngagement(userId, command.previousPeriod().startAt(),
				command.currentPeriod().endAt()).stream().filter(item -> period(item, command) != null).toList();
		var composedAt = clock.instant();
		var summary = new ReassessmentSummaryView(UUID.randomUUID(), SUMMARY_VERSION, composedAt,
				command.previousPeriod(), command.currentPeriod(), screening,
				journalDimension(command.journalAnalysisId(), projection), engagement(command, engagementEvidence),
				reflections(command, engagementEvidence), DISCLAIMER);
		var stored = summaries.persist(summary.summaryId(), userId, idempotencyKey, requestHash,
				command.journalAnalysisId(), command.previousPeriod(), command.currentPeriod(), serialize(summary), composedAt);
		return replay(stored, requestHash);
	}

	public ReassessmentSummaryView get(UUID userId, UUID summaryId) {
		return summaries.find(userId, summaryId).map(this::deserialize).orElseThrow(this::notFound);
	}

	public ReassessmentSummaryView current(UUID userId) {
		return summaries.current(userId).map(this::deserialize).orElseThrow(this::notFound);
	}

	public HistoryView history(UUID userId, int limit, String cursor) {
		Cursor decoded = decode(cursor);
		var rows = summaries.history(userId, decoded == null ? null : decoded.time(),
				decoded == null ? null : decoded.id(), limit + 1);
		boolean hasMore = rows.size() > limit;
		var page = rows.stream().limit(limit).map(this::deserialize).toList();
		String nextCursor = hasMore ? encode(rows.get(limit - 1).composedAt(), rows.get(limit - 1).id()) : null;
		return new HistoryView(page, nextCursor, hasMore);
	}

	private ScreeningTrend screeningTrend(UUID userId, UUID assessmentId, String expectedInstrument) {
		AssessmentEvidence current = evidence.findAssessment(userId, assessmentId).orElseThrow(
				() -> new ApiException(HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND", "Assessment was not found"));
		if (!expectedInstrument.equals(current.instrument()) || !current.usable()) {
			throw new ApiException(HttpStatus.CONFLICT, "ASSESSMENT_NOT_COMPARABLE",
					"Selected assessment is not valid reassessment evidence");
		}
		var currentPoint = point(current);
		var previous = evidence.findPrevious(userId, current);
		if (previous.isEmpty()) {
			return new ScreeningTrend(expectedInstrument, "INSUFFICIENT_DATA", current.scoringVersion(), null,
					currentPoint, null, "INSUFFICIENT_DATA");
		}
		int delta = current.totalScore() - previous.get().totalScore();
		String direction = delta < 0 ? "DECREASED" : delta > 0 ? "INCREASED" : "UNCHANGED";
		return new ScreeningTrend(expectedInstrument, "AVAILABLE", current.scoringVersion(), point(previous.get()),
				currentPoint, delta, direction);
	}

	private ScreeningPoint point(AssessmentEvidence value) {
		return new ScreeningPoint(value.assessmentId(), value.questionnaireVersion(), value.submittedAt(),
				value.totalScore(), value.screeningLevel());
	}

	private JournalDimension journalDimension(UUID analysisId, Projection projection) {
		var result = projection.evidence();
		if (result == null) {
			return new JournalDimension("UNAVAILABLE", projection.unavailableReason(), analysisId, List.of(), List.of(),
					List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
		}
		var sources = result.sourceJournalRevisions().stream()
				.map(source -> new JournalSourceRevision(source.journalId(), source.journalRevision(), source.period()))
				.toList();
		var changes = result.changesComparedWithPreviousPeriod().stream()
				.map(change -> new JournalChange(change.signal(), change.direction())).toList();
		var coverage = new JournalCoverage(result.dataCoverage().previousPeriodJournalEntryCount(),
				result.dataCoverage().currentPeriodJournalEntryCount(),
				result.dataCoverage().sufficientForComparison());
		var provenance = new JournalProvenance(result.provider(), result.model(), result.promptVersion(),
				result.schemaVersion(), result.createdAt());
		return new JournalDimension(projection.state(), null, analysisId, sources, result.contextSignals(),
				result.emotionIndicators(), result.recurringThemes(), changes, result.preferences(), result.barriers(),
				result.helpfulPatterns(), coverage, provenance);
	}

	private EngagementDimension engagement(ComposeCommand command, List<EngagementEvidence> rows) {
		var sources = rows.stream().map(row -> new EngagementSource(row.occurrenceId(), row.supportPlanId(),
				row.sourcePlanVersion(), row.sourceSlotId(), row.sourceResourceId(), row.sourceContentVersion(),
				row.scheduledAt(), period(row, command), row.state(), row.barrierCode())).toList();
		var previous = engagementPeriod(rows, command, "PREVIOUS");
		var current = engagementPeriod(rows, command, "CURRENT");
		String state = previous.completedCount() + previous.skippedCount() > 0
				&& current.completedCount() + current.skippedCount() > 0 ? "AVAILABLE" : "INSUFFICIENT_DATA";
		return new EngagementDimension(state, previous, current, sources);
	}

	private EngagementPeriod engagementPeriod(List<EngagementEvidence> rows, ComposeCommand command, String period) {
		var selected = rows.stream().filter(row -> period.equals(period(row, command))).toList();
		return new EngagementPeriod((int) selected.stream().filter(row -> "COMPLETED".equals(row.state())).count(),
				(int) selected.stream().filter(row -> "SKIPPED".equals(row.state())).count());
	}

	private ReflectionDimension reflections(ComposeCommand command, List<EngagementEvidence> rows) {
		var sources = rows.stream().filter(row -> row.helpfulness() != null || row.reflection() != null)
				.map(row -> new ReflectionSource(row.occurrenceId(), period(row, command), row.helpfulness(),
						row.reflection(), row.engagementUpdatedAt())).toList();
		boolean previous = sources.stream().anyMatch(source -> "PREVIOUS".equals(source.period()));
		boolean current = sources.stream().anyMatch(source -> "CURRENT".equals(source.period()));
		return new ReflectionDimension(previous && current ? "AVAILABLE" : "INSUFFICIENT_DATA", sources);
	}

	private String period(EngagementEvidence evidence, ComposeCommand command) {
		if (!evidence.scheduledAt().isBefore(command.previousPeriod().startAt())
				&& evidence.scheduledAt().isBefore(command.previousPeriod().endAt())) return "PREVIOUS";
		if (!evidence.scheduledAt().isBefore(command.currentPeriod().startAt())
				&& evidence.scheduledAt().isBefore(command.currentPeriod().endAt())) return "CURRENT";
		return null;
	}

	private void validatePeriods(Period previous, Period current) {
		if (previous == null || current == null || previous.startAt() == null || previous.endAt() == null
				|| current.startAt() == null || current.endAt() == null) throw invalidPeriods();
		Duration previousDuration = Duration.between(previous.startAt(), previous.endAt());
		Duration currentDuration = Duration.between(current.startAt(), current.endAt());
		if (previousDuration.compareTo(MINIMUM_PERIOD) < 0 || previousDuration.compareTo(MAXIMUM_PERIOD) > 0
				|| !previousDuration.equals(currentDuration) || previous.endAt().isAfter(current.startAt())
				|| current.endAt().isAfter(clock.instant())) throw invalidPeriods();
	}

	private ApiException invalidPeriods() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REASSESSMENT_PERIODS",
				"Reassessment periods must be equal, non-overlapping, and between 7 and 31 days");
	}

	private String hash(ComposeCommand command) {
		String value = command.phq9AssessmentId() + "|" + command.gad7AssessmentId() + "|"
				+ command.journalAnalysisId() + "|" + command.previousPeriod().startAt() + "|"
				+ command.previousPeriod().endAt() + "|" + command.currentPeriod().startAt() + "|"
				+ command.currentPeriod().endAt();
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ReassessmentSummaryView replay(StoredSummary stored, String requestHash) {
		if (!stored.requestHash().equals(requestHash)) {
			throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
					"Idempotency-Key was already used with different reassessment evidence");
		}
		return deserialize(stored);
	}

	private String serialize(ReassessmentSummaryView summary) {
		try {
			return objectMapper.writeValueAsString(summary);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Reassessment summary could not be serialized", exception);
		}
	}

	private ReassessmentSummaryView deserialize(StoredSummary stored) {
		try {
			return objectMapper.readValue(stored.snapshot(), ReassessmentSummaryView.class);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Persisted reassessment summary is invalid", exception);
		}
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "REASSESSMENT_SUMMARY_NOT_FOUND",
				"Reassessment summary was not found");
	}

	private String encode(Instant time, UUID id) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString((time + "|" + id).getBytes(StandardCharsets.UTF_8));
	}

	private Cursor decode(String value) {
		if (value == null) return null;
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			String[] parts = decoded.split("\\|", -1);
			if (parts.length != 2) throw new IllegalArgumentException();
			return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
		}
		catch (RuntimeException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "History cursor is invalid");
		}
	}

	public record ComposeCommand(UUID phq9AssessmentId, UUID gad7AssessmentId, UUID journalAnalysisId,
			Period previousPeriod, Period currentPeriod) { }

	public record HistoryView(List<ReassessmentSummaryView> items, String nextCursor, boolean hasMore) { }

	private record Cursor(Instant time, UUID id) { }
}
