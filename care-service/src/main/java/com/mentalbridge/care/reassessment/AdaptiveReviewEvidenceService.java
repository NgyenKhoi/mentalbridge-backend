package com.mentalbridge.care.reassessment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.reassessment.ReassessmentEvidenceRepository.EngagementEvidence;
import com.mentalbridge.care.reassessment.ReassessmentSelfReportStore.StoredSelfReport;
import com.mentalbridge.care.reassessment.ReassessmentSummaryService.ReassessmentContextView;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ExplicitSelfReport;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.JournalDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ReflectionDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.SelfReportedExperienceDimension;
import com.mentalbridge.care.shared.ApiException;

@Service
public class AdaptiveReviewEvidenceService {

	private final ReassessmentSummaryService summaries;
	private final ReassessmentEvidenceRepository evidence;
	private final ReassessmentSelfReportStore reports;
	private final JournalLongitudinalClient journal;
	private final ObjectMapper mapper;
	private final Clock clock;

	public AdaptiveReviewEvidenceService(ReassessmentSummaryService summaries,
			ReassessmentEvidenceRepository evidence, ReassessmentSelfReportStore reports,
			JournalLongitudinalClient journal, ObjectMapper mapper, Clock clock) {
		this.summaries = summaries;
		this.evidence = evidence;
		this.reports = reports;
		this.journal = journal;
		this.mapper = mapper;
		this.clock = clock;
	}

	public Context read(UUID userId, UUID summaryId, String bearerToken, UUID correlationId) {
		var comparison = summaries.context(userId);
		var latest = current(userId);
		var selected = summaryId == null ? latest.orElse(null) : summaries.get(userId, summaryId);
		var previousPeriod = selected == null ? comparison.previousPeriod() : selected.previousPeriod();
		var currentPeriod = selected == null ? comparison.currentPeriod() : selected.currentPeriod();
		var report = report(userId, selected, currentPeriod);
		var selfReport = selfReport(report, currentPeriod);
		var occurrences = occurrences(userId, currentPeriod);
		var previousOccurrences = occurrences(userId, previousPeriod);
		var local = localSnapshot(userId, selected, comparison, latest.map(ReassessmentSummaryView::summaryId).orElse(null),
				report, occurrences, previousOccurrences);
		String state = selected == null
				? comparison.phq9AssessmentId() == null && comparison.gad7AssessmentId() == null
						? "NOT_STARTED" : "INCOMPLETE"
				: "AVAILABLE";
		String reason = selected == null ? "REASSESSMENT_SUMMARY_NOT_FOUND" : null;
		if (selected != null && (!latest.map(ReassessmentSummaryView::summaryId).filter(selected.summaryId()::equals)
				.isPresent() || !ReassessmentSummaryService.SUMMARY_VERSION.equals(selected.summaryVersion())
				|| selected.currentPeriod().endAt().isBefore(clock.instant().minus(Duration.ofHours(24)))
				|| selected.currentPeriod().endAt().isAfter(clock.instant())
				|| !matchesScreeningContext(selected, comparison))) {
			state = "STALE";
			reason = "REASSESSMENT_SUMMARY_STALE";
		}
		if (selected != null && unavailableScreening(selected, local.assessments())) {
			state = "STALE";
			reason = "SOURCE_DELETED";
		}
		if (selected != null && changedSelfReport(selected, report)) {
			state = "STALE";
			reason = report.filter(StoredSelfReport::deleted).isPresent()
					? "SOURCE_DELETED" : "SOURCE_REVISION_CHANGED";
		}
		var currentJournal = selected == null ? null
				: currentJournal(userId, selected, bearerToken, correlationId);
		var safeSummary = selected == null ? null
				: safeSummary(selected, selfReport, currentJournal, occurrences, previousOccurrences);
		String localToken = hash(local);
		String sourceToken = hash(List.of(localToken, state, Objects.toString(reason, ""),
				currentJournal == null ? "NOT_PROVIDED" : currentJournal));
		return new Context(comparison, state, reason, safeSummary, selfReport, occurrences, localToken,
				sourceToken, previousPeriod, currentPeriod);
	}

	public String localToken(UUID userId, Context observed) {
		var selected = observed.summary() == null ? null : summaries.get(userId, observed.summary().summaryId());
		return hash(localSnapshot(userId, selected, summaries.context(userId),
				current(userId).map(ReassessmentSummaryView::summaryId).orElse(null),
				report(userId, selected, observed.currentPeriod()), occurrences(userId, observed.currentPeriod()),
				occurrences(userId, observed.previousPeriod())));
	}

	private Optional<ReassessmentSummaryView> current(UUID userId) {
		try {
			return Optional.of(summaries.current(userId));
		}
		catch (ApiException exception) {
			if (!"REASSESSMENT_SUMMARY_NOT_FOUND".equals(exception.code())) throw exception;
			return Optional.empty();
		}
	}

	private Optional<StoredSelfReport> report(UUID userId, ReassessmentSummaryView summary, Period period) {
		if (summary != null && summary.selfReportedExperience() != null
				&& summary.selfReportedExperience().source() != null) {
			return reports.find(userId, summary.selfReportedExperience().source().selfReportId());
		}
		return reports.currentForPeriod(userId, period);
	}

	private SelfReportedExperienceDimension selfReport(Optional<StoredSelfReport> report, Period period) {
		if (report.isEmpty()) return new SelfReportedExperienceDimension("INSUFFICIENT_DATA", "NOT_PROVIDED", null);
		var value = report.get();
		if (value.deleted()) return new SelfReportedExperienceDimension("UNAVAILABLE", "SOURCE_DELETED", null);
		if (!value.periodStart().equals(period.startAt()) || !value.periodEnd().equals(period.endAt())) {
			return new SelfReportedExperienceDimension("INSUFFICIENT_DATA", "NOT_PROVIDED", null);
		}
		return new SelfReportedExperienceDimension("AVAILABLE", null, new ExplicitSelfReport(value.id(),
				value.sourceVersion(), value.version(), period, value.currentExperience(), value.helpfulContext(),
				value.difficultContext(), value.authoredAt(), value.updatedAt()));
	}

	private boolean changedSelfReport(ReassessmentSummaryView summary, Optional<StoredSelfReport> report) {
		var source = summary.selfReportedExperience() == null ? null : summary.selfReportedExperience().source();
		return source != null && (report.isEmpty() || report.get().deleted()
				|| !source.sourceVersion().equals(report.get().sourceVersion())
				|| source.sourceRevision() != report.get().version());
	}

	private boolean matchesScreeningContext(ReassessmentSummaryView summary, ReassessmentContextView context) {
		return summary.screening().trends().stream().allMatch(trend -> trend.current() != null
				&& trend.current().assessmentId().equals("PHQ9".equals(trend.instrument())
						? context.phq9AssessmentId() : context.gad7AssessmentId()));
	}

	private boolean unavailableScreening(ReassessmentSummaryView summary, List<ReassessmentEvidenceRepository.AssessmentEvidence> sources) {
		return summary.screening().trends().stream().anyMatch(trend -> trend.current() == null
				|| sources.stream().noneMatch(item -> item.assessmentId().equals(trend.current().assessmentId()) && item.usable())
				|| trend.previous() != null && sources.stream()
						.noneMatch(item -> item.assessmentId().equals(trend.previous().assessmentId()) && item.usable()));
	}

	private JournalDimension currentJournal(UUID userId, ReassessmentSummaryView summary, String token, UUID correlationId) {
		var snapshot = summary.journalContext();
		if (snapshot.analysisId() == null && snapshot.jobId() == null) return snapshot;
		JournalLongitudinalClient.Projection projection;
		try {
			projection = snapshot.analysisId() != null
					? journal.read(userId, snapshot.analysisId(), summary.previousPeriod(), summary.currentPeriod(), token,
							correlationId)
					: journal.resolveJob(userId, snapshot.jobId(), summary.previousPeriod(), summary.currentPeriod(), token,
							correlationId);
		}
		catch (JournalLongitudinalClient.AnalysisInProgressException exception) {
			return unavailableJournal(snapshot, "DEPENDENCY_UNAVAILABLE");
		}
		if (projection == null) return unavailableJournal(snapshot, "DEPENDENCY_UNAVAILABLE");
		if ("UNAVAILABLE".equals(projection.state())) {
			return unavailableJournal(snapshot, projection.unavailableReason());
		}
		if (snapshot.analysisId() == null || !snapshot.analysisId().equals(projection.analysisId())) {
			return unavailableJournal(snapshot, "SOURCE_REVISION_CHANGED");
		}
		var revisions = projection.evidence() == null ? List.of()
				: projection.evidence().sourceJournalRevisions().stream()
						.map(item -> new ReassessmentSummaryView.JournalSourceRevision(item.journalId(),
								item.journalRevision(), item.period())).toList();
		if (!snapshot.sourceJournalRevisions().equals(revisions)) {
			return unavailableJournal(snapshot, "SOURCE_REVISION_CHANGED");
		}
		return snapshot;
	}

	private JournalDimension unavailableJournal(JournalDimension source, String reason) {
		return new JournalDimension("UNAVAILABLE", reason, source.jobId(), source.analysisId(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
	}

	private ReassessmentSummaryView safeSummary(ReassessmentSummaryView summary,
			SelfReportedExperienceDimension report, JournalDimension currentJournal,
			List<OccurrenceEvidence> current, List<OccurrenceEvidence> previous) {
		var all = new ArrayList<OccurrenceEvidence>(previous);
		all.addAll(current);
		var sources = all.stream().map(item -> new ReassessmentSummaryView.EngagementSource(item.occurrenceId(),
				item.supportPlanId(), item.sourcePlanVersion(), item.sourceSlotId(), item.sourceResourceId(),
				item.sourceContentVersion(), item.scheduledAt(),
				item.scheduledAt().isBefore(summary.currentPeriod().startAt()) ? "PREVIOUS" : "CURRENT", item.state(),
				item.barrierCode())).toList();
		var previousCounts = new ReassessmentSummaryView.EngagementPeriod(
				(int) previous.stream().filter(item -> "COMPLETED".equals(item.state())).count(),
				(int) previous.stream().filter(item -> "SKIPPED".equals(item.state())).count());
		var currentCounts = new ReassessmentSummaryView.EngagementPeriod(
				(int) current.stream().filter(item -> "COMPLETED".equals(item.state())).count(),
				(int) current.stream().filter(item -> "SKIPPED".equals(item.state())).count());
		String engagementState = previous.isEmpty() || current.isEmpty() ? "INSUFFICIENT_DATA" : "AVAILABLE";
		var freshEngagement = new ReassessmentSummaryView.EngagementDimension(engagementState, previousCounts,
				currentCounts, sources);
		return new ReassessmentSummaryView(summary.summaryId(), summary.summaryVersion(), summary.composedAt(),
				summary.previousPeriod(), summary.currentPeriod(), summary.screening(), currentJournal,
				freshEngagement, report, safeReflections(summary.activityReflection(), all, summary.currentPeriod()),
				safeReflections(summary.userReflection(), all, summary.currentPeriod()), summary.disclaimerCode());
	}

	private ReflectionDimension safeReflections(ReflectionDimension source, List<OccurrenceEvidence> occurrences,
			Period current) {
		if (source == null) return null;
		var sources = occurrences.stream().filter(item -> item.helpfulness() != null)
				.map(item -> new ReassessmentSummaryView.ReflectionSource(item.occurrenceId(),
						item.scheduledAt().isBefore(current.startAt()) ? "PREVIOUS" : "CURRENT", item.helpfulness(), null,
						item.engagementUpdatedAt())).toList();
		boolean previousPresent = sources.stream().anyMatch(item -> "PREVIOUS".equals(item.period()));
		boolean currentPresent = sources.stream().anyMatch(item -> "CURRENT".equals(item.period()));
		return new ReflectionDimension(previousPresent && currentPresent ? "AVAILABLE" : "INSUFFICIENT_DATA", sources);
	}

	private List<OccurrenceEvidence> occurrences(UUID userId, Period period) {
		return evidence.findReusableEngagement(userId, period.startAt(), period.endAt()).stream()
				.map(this::occurrence).toList();
	}

	private OccurrenceEvidence occurrence(EngagementEvidence item) {
		return new OccurrenceEvidence(item.occurrenceId(), item.occurrenceVersion(), item.actionId(),
				item.actionVersion(), item.supportPlanId(), item.sourcePlanVersion(), item.sourceSlotId(),
				item.sourceResourceId(), item.actionId() == null ? item.sourceContentVersion() : null,
				item.scheduledAt(), item.state(), item.helpfulness(), item.barrierCode(), item.engagementUpdatedAt());
	}

	private LocalSnapshot localSnapshot(UUID userId, ReassessmentSummaryView selected,
			ReassessmentContextView comparison, UUID latestSummaryId, Optional<StoredSelfReport> sourceReport,
			List<OccurrenceEvidence> currentOccurrences, List<OccurrenceEvidence> previousOccurrences) {
		var assessments = new ArrayList<ReassessmentEvidenceRepository.AssessmentEvidence>();
		if (selected != null) {
			for (var trend : selected.screening().trends()) {
				if (trend.previous() != null) evidence.findAssessment(userId, trend.previous().assessmentId())
						.ifPresent(assessments::add);
				if (trend.current() != null) evidence.findAssessment(userId, trend.current().assessmentId())
						.ifPresent(assessments::add);
			}
		}
		var report = sourceReport.map(item -> new ReportIdentity(item.id(), item.sourceVersion(),
				item.version(), item.periodStart(), item.periodEnd(), item.deletedAt())).orElse(null);
		return new LocalSnapshot(comparison, latestSummaryId,
				selected == null ? null : selected.summaryId(), report, assessments, currentOccurrences, previousOccurrences);
	}

	private String hash(Object value) {
		try {
			String serialized = mapper.writeValueAsString(value);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(serialized.getBytes(StandardCharsets.UTF_8)));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Adaptive review evidence could not be serialized", exception);
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public record Context(ReassessmentContextView comparisonContext, String state, String reasonCode,
			ReassessmentSummaryView summary, SelfReportedExperienceDimension selfReport,
			List<OccurrenceEvidence> occurrences, String localToken, String sourceToken,
			Period previousPeriod, Period currentPeriod) { }

	public record OccurrenceEvidence(UUID occurrenceId, long occurrenceVersion, UUID actionId, Long actionVersion,
			UUID supportPlanId, long sourcePlanVersion, String sourceSlotId, UUID sourceResourceId,
			Long sourceContentVersion, Instant scheduledAt, String state, String helpfulness, String barrierCode,
			Instant engagementUpdatedAt) { }

	private record ReportIdentity(UUID id, String sourceVersion, long revision, Instant periodStart,
			Instant periodEnd, Instant deletedAt) { }

	private record LocalSnapshot(ReassessmentContextView context, UUID latestSummaryId, UUID selectedSummaryId,
			ReportIdentity selfReport, List<ReassessmentEvidenceRepository.AssessmentEvidence> assessments,
			List<OccurrenceEvidence> occurrences, List<OccurrenceEvidence> previousOccurrences) { }
}
