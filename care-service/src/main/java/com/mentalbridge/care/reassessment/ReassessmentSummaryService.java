package com.mentalbridge.care.reassessment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ExplicitSelfReport;
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
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.SelfReportedExperienceDimension;
import com.mentalbridge.care.screeningepisode.ScreeningEpisodeService;
import com.mentalbridge.care.shared.ApiException;

@Service
public class ReassessmentSummaryService {

	static final String LEGACY_SUMMARY_VERSION = "reassessment-summary-v1";
	static final String SUMMARY_VERSION = "reassessment-summary-v2";
	static final String COMPARISON_POLICY_VERSION = "reassessment-comparison-v1";
	private static final String DISCLAIMER = "FOUR_DIMENSIONS_NOT_COMBINED";
	private static final Duration MINIMUM_PERIOD = Duration.ofDays(7);
	private static final Duration MAXIMUM_PERIOD = Duration.ofDays(31);
	private static final Duration COMPARISON_PERIOD = Duration.ofDays(14);
	private static final Duration MAXIMUM_CONTEXT_AGE = Duration.ofHours(24);

	private final ReassessmentEvidenceRepository evidence;
	private final JournalLongitudinalClient journal;
	private final ReassessmentSummaryStore summaries;
	private final ReassessmentSelfReportService selfReports;
	private final ScreeningEpisodeService screeningEpisodes;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public ReassessmentSummaryService(ReassessmentEvidenceRepository evidence, JournalLongitudinalClient journal,
			ReassessmentSummaryStore summaries, ReassessmentSelfReportService selfReports,
			ScreeningEpisodeService screeningEpisodes, ObjectMapper objectMapper, Clock clock) {
		this.evidence = evidence;
		this.journal = journal;
		this.summaries = summaries;
		this.selfReports = selfReports;
		this.screeningEpisodes = screeningEpisodes;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public ReassessmentSummaryView compose(UUID userId, String bearerToken, String idempotencyKey,
			UUID correlationId, ComposeCommand command) {
		boolean jobFlow = validateEvidenceReference(command);
		if (jobFlow) {
			validatePolicyPeriods(command.previousPeriod(), command.currentPeriod());
			var episode = screeningEpisodes.requiredCompleted(userId, "REASSESSMENT");
			validateEpisodeAssessment(command.phq9AssessmentId(), episode.phq9AssessmentId(), "PHQ9");
			validateEpisodeAssessment(command.gad7AssessmentId(), episode.gad7AssessmentId(), "GAD7");
		}
		else {
			validateLegacyPeriods(command.previousPeriod(), command.currentPeriod());
		}
		String requestHash = hash(command);
		var replay = summaries.findByRequest(userId, idempotencyKey);
		if (replay.isPresent()) return replay(replay.get(), requestHash);

		var phq9 = screeningTrend(userId, command.phq9AssessmentId(), "PHQ9");
		var gad7 = screeningTrend(userId, command.gad7AssessmentId(), "GAD7");
		var screening = new ScreeningDimension(
				"AVAILABLE".equals(phq9.state()) && "AVAILABLE".equals(gad7.state())
						? "AVAILABLE" : "INSUFFICIENT_DATA",
				List.of(phq9, gad7));
		Projection projection;
		try {
			projection = jobFlow
					? journal.resolveJob(userId, command.journalJobId(), command.previousPeriod(),
							command.currentPeriod(), bearerToken, correlationId)
					: journal.read(userId, command.journalAnalysisId(), command.previousPeriod(),
							command.currentPeriod(), bearerToken, correlationId);
		}
		catch (JournalLongitudinalClient.AnalysisInProgressException exception) {
			throw new ApiException(HttpStatus.CONFLICT, "JOURNAL_ANALYSIS_IN_PROGRESS",
					"Journal longitudinal analysis is still running");
		}
		if (!jobFlow && projection.analysisId() == null) {
			projection = new Projection(null, command.journalAnalysisId(), projection.state(),
					projection.unavailableReason(), projection.evidence());
		}
		var engagementEvidence = evidence.findReusableEngagement(userId, command.previousPeriod().startAt(),
				command.currentPeriod().endAt()).stream().filter(item -> period(item, command) != null).toList();
		var composedAt = clock.instant();
		String summaryVersion = jobFlow ? SUMMARY_VERSION : LEGACY_SUMMARY_VERSION;
		var activityReflection = reflections(command, engagementEvidence);
		var summary = new ReassessmentSummaryView(UUID.randomUUID(), summaryVersion, composedAt,
				command.previousPeriod(), command.currentPeriod(), screening,
				journalDimension(projection), engagement(command, engagementEvidence),
				jobFlow ? selfReportedExperience(userId, command) : null,
				jobFlow ? activityReflection : null, jobFlow ? null : activityReflection, DISCLAIMER);
		var stored = summaries.persist(summary.summaryId(), userId, idempotencyKey, requestHash, summaryVersion,
				projection.jobId(), projection.analysisId(), command.previousPeriod(), command.currentPeriod(),
				serialize(summary), composedAt);
		return replay(stored, requestHash);
	}

	public ReassessmentContextView context(UUID userId) {
		Instant end = clock.instant().truncatedTo(ChronoUnit.DAYS);
		var current = new Period(end.minus(COMPARISON_PERIOD), end);
		var previous = new Period(current.startAt().minus(COMPARISON_PERIOD), current.startAt());
		ScreeningEpisodeService.EpisodeView episode;
		try {
			episode = screeningEpisodes.current(userId, "REASSESSMENT");
		}
		catch (ApiException exception) {
			if (!"SCREENING_EPISODE_NOT_FOUND".equals(exception.code())) throw exception;
			episode = null;
		}
		var phq9 = episode == null || episode.phq9AssessmentId() == null
				? java.util.Optional.<AssessmentEvidence>empty()
				: evidence.findAssessment(userId, episode.phq9AssessmentId()).filter(AssessmentEvidence::usable);
		var gad7 = episode == null || episode.gad7AssessmentId() == null
				? java.util.Optional.<AssessmentEvidence>empty()
				: evidence.findAssessment(userId, episode.gad7AssessmentId()).filter(AssessmentEvidence::usable);
		var missing = new ArrayList<String>();
		if (phq9.isEmpty()) missing.add("PHQ9");
		if (gad7.isEmpty()) missing.add("GAD7");
		return new ReassessmentContextView(COMPARISON_POLICY_VERSION,
				missing.isEmpty() ? "READY" : "INCOMPLETE", List.copyOf(missing),
				phq9.map(AssessmentEvidence::assessmentId).orElse(null),
				gad7.map(AssessmentEvidence::assessmentId).orElse(null), previous, current);
	}

	public ReassessmentSummaryView get(UUID userId, UUID summaryId) {
		return summaries.find(userId, summaryId).map(this::deserialize).orElseThrow(this::notFound);
	}

	public ReassessmentSummaryView current(UUID userId) {
		return summaries.current(userId).map(this::deserialize).orElseThrow(this::notFound);
	}

	public ReassessmentSummaryView currentForPlanReview(UUID userId, UUID summaryId) {
		var summary = current(userId);
		if (!summary.summaryId().equals(summaryId) || !SUMMARY_VERSION.equals(summary.summaryVersion())
				|| summary.currentPeriod().endAt().isBefore(clock.instant().minus(MAXIMUM_CONTEXT_AGE))) {
			throw new ApiException(HttpStatus.CONFLICT, "REASSESSMENT_SUMMARY_STALE",
					"A current canonical reassessment summary is required for SupportPlan review");
		}
		return summary;
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

	private JournalDimension journalDimension(Projection projection) {
		var result = projection.evidence();
		if (result == null) {
			return new JournalDimension(projection.state(), projection.unavailableReason(), projection.jobId(),
					projection.analysisId(), List.of(), List.of(),
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
		return new JournalDimension(projection.state(), null, projection.jobId(), projection.analysisId(), sources,
				result.contextSignals(),
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

	private SelfReportedExperienceDimension selfReportedExperience(UUID userId, ComposeCommand command) {
		if (command.selfReportId() == null) {
			return new SelfReportedExperienceDimension("INSUFFICIENT_DATA", "NOT_PROVIDED", null);
		}
		var report = selfReports.evidence(userId, command.selfReportId());
		if (!report.periodStart().equals(command.currentPeriod().startAt())
				|| !report.periodEnd().equals(command.currentPeriod().endAt())) {
			throw new ApiException(HttpStatus.CONFLICT, "SELF_REPORT_PERIOD_MISMATCH",
					"Reassessment self-report does not cover the requested current period");
		}
		if (report.deleted()) {
			return new SelfReportedExperienceDimension("UNAVAILABLE", "SOURCE_DELETED", null);
		}
		var source = new ExplicitSelfReport(report.id(), report.sourceVersion(), report.version(),
				new Period(report.periodStart(), report.periodEnd()), report.currentExperience(), report.helpfulContext(),
				report.difficultContext(), report.authoredAt(), report.updatedAt());
		return new SelfReportedExperienceDimension("AVAILABLE", null, source);
	}

	private String period(EngagementEvidence evidence, ComposeCommand command) {
		if (!evidence.scheduledAt().isBefore(command.previousPeriod().startAt())
				&& evidence.scheduledAt().isBefore(command.previousPeriod().endAt())) return "PREVIOUS";
		if (!evidence.scheduledAt().isBefore(command.currentPeriod().startAt())
				&& evidence.scheduledAt().isBefore(command.currentPeriod().endAt())) return "CURRENT";
		return null;
	}

	private boolean validateEvidenceReference(ComposeCommand command) {
		boolean analysis = command.journalAnalysisId() != null;
		boolean job = command.journalJobId() != null;
		if (analysis == job) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JOURNAL_EVIDENCE_REFERENCE",
					"Exactly one of journalAnalysisId or journalJobId is required");
		}
		return job;
	}

	private void validateEpisodeAssessment(UUID selectedId, UUID episodeAssessmentId, String instrument) {
		if (episodeAssessmentId == null || !episodeAssessmentId.equals(selectedId)) {
			throw new ApiException(HttpStatus.CONFLICT, "REASSESSMENT_SCREENING_CONTEXT_MISMATCH",
					"Selected " + instrument + " assessment does not belong to the current reassessment episode");
		}
	}

	private void validateLegacyPeriods(Period previous, Period current) {
		if (previous == null || current == null || previous.startAt() == null || previous.endAt() == null
				|| current.startAt() == null || current.endAt() == null) throw invalidPeriods();
		Duration previousDuration = Duration.between(previous.startAt(), previous.endAt());
		Duration currentDuration = Duration.between(current.startAt(), current.endAt());
		if (previousDuration.compareTo(MINIMUM_PERIOD) < 0 || previousDuration.compareTo(MAXIMUM_PERIOD) > 0
				|| !previousDuration.equals(currentDuration) || previous.endAt().isAfter(current.startAt())
				|| current.endAt().isAfter(clock.instant())) throw invalidPeriods();
	}

	private void validatePolicyPeriods(Period previous, Period current) {
		if (previous == null || current == null || previous.startAt() == null || previous.endAt() == null
				|| current.startAt() == null || current.endAt() == null) throw invalidPolicyPeriods();
		Instant now = clock.instant();
		if (!Duration.between(previous.startAt(), previous.endAt()).equals(COMPARISON_PERIOD)
				|| !Duration.between(current.startAt(), current.endAt()).equals(COMPARISON_PERIOD)
				|| !previous.endAt().equals(current.startAt()) || current.endAt().isAfter(now)
				|| Duration.between(current.endAt(), now).compareTo(MAXIMUM_CONTEXT_AGE) > 0) {
			throw invalidPolicyPeriods();
		}
	}

	private ApiException invalidPolicyPeriods() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REASSESSMENT_POLICY_PERIODS",
				"Periods must match the current versioned reassessment comparison policy");
	}

	private ApiException invalidPeriods() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REASSESSMENT_PERIODS",
				"Reassessment periods must be equal, non-overlapping, and between 7 and 31 days");
	}

	private String hash(ComposeCommand command) {
		String value = command.phq9AssessmentId() + "|" + command.gad7AssessmentId() + "|"
				+ command.journalAnalysisId() + "|" + command.journalJobId() + "|"
				+ command.previousPeriod().startAt() + "|"
				+ command.previousPeriod().endAt() + "|" + command.currentPeriod().startAt() + "|"
				+ command.currentPeriod().endAt() + "|" + command.selfReportId();
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
			UUID journalJobId,
			Period previousPeriod, Period currentPeriod, UUID selfReportId) { }

	public record ReassessmentContextView(String policyVersion, String state, List<String> missingInstruments,
			UUID phq9AssessmentId, UUID gad7AssessmentId, Period previousPeriod, Period currentPeriod) { }

	public record HistoryView(List<ReassessmentSummaryView> items, String nextCursor, boolean hasMore) { }

	private record Cursor(Instant time, UUID id) { }
}
