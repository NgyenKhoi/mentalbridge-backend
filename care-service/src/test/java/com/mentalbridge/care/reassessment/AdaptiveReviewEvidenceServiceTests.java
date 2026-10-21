package com.mentalbridge.care.reassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.reassessment.ReassessmentEvidenceRepository.AssessmentEvidence;
import com.mentalbridge.care.reassessment.ReassessmentEvidenceRepository.EngagementEvidence;
import com.mentalbridge.care.reassessment.ReassessmentSelfReportStore.StoredSelfReport;
import com.mentalbridge.care.reassessment.ReassessmentSummaryService.ReassessmentContextView;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.EngagementDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.EngagementPeriod;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ExplicitSelfReport;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.JournalDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ReflectionDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ReflectionSource;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ScreeningDimension;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ScreeningPoint;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ScreeningTrend;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.SelfReportedExperienceDimension;
import com.mentalbridge.care.shared.ApiException;

class AdaptiveReviewEvidenceServiceTests {

	private static final UUID USER = UUID.randomUUID();
	private static final UUID PLAN = UUID.randomUUID();
	private static final UUID ACTION = UUID.randomUUID();
	private static final UUID PHQ9 = UUID.randomUUID();
	private static final UUID GAD7 = UUID.randomUUID();
	private static final UUID REPORT = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
	private static final Period CURRENT = new Period(Instant.parse("2026-10-07T00:00:00Z"),
			Instant.parse("2026-10-21T00:00:00Z"));
	private static final Period PREVIOUS = new Period(Instant.parse("2026-09-23T00:00:00Z"), CURRENT.startAt());
	private static final ReassessmentContextView COMPARISON = new ReassessmentContextView(
			"reassessment-comparison-v1", "READY", List.of(), PHQ9, GAD7, PREVIOUS, CURRENT);
	private final ReassessmentSummaryService summaries = mock(ReassessmentSummaryService.class);
	private final ReassessmentEvidenceRepository evidence = mock(ReassessmentEvidenceRepository.class);
	private final ReassessmentSelfReportStore reports = mock(ReassessmentSelfReportStore.class);
	private final JournalLongitudinalClient journal = mock(JournalLongitudinalClient.class);
	private final Clock clock = mock(Clock.class);
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	private final AdaptiveReviewEvidenceService service = new AdaptiveReviewEvidenceService(summaries, evidence,
			reports, journal, mapper, clock);

	@BeforeEach
	void context() {
		when(clock.instant()).thenReturn(NOW);
		when(summaries.context(USER)).thenReturn(COMPARISON);
		when(summaries.current(USER)).thenThrow(noSummary());
		when(evidence.findReusableEngagement(any(), any(), any())).thenReturn(List.of());
		when(reports.currentForPeriod(USER, CURRENT)).thenReturn(Optional.empty());
		when(evidence.findAssessment(USER, PHQ9)).thenReturn(Optional.of(assessment(PHQ9, "PHQ9")));
		when(evidence.findAssessment(USER, GAD7)).thenReturn(Optional.of(assessment(GAD7, "GAD7")));
	}

	@Test
	void readsExactPeriodSelfReportAndNativeApprovedSourceIdentitiesWithoutComposingSummary() throws Exception {
		when(reports.currentForPeriod(USER, CURRENT)).thenReturn(Optional.of(report(4, false)));
		var occurrence = engagement(UUID.randomUUID(), 7, "LOW_ENERGY");
		when(evidence.findReusableEngagement(USER, CURRENT.startAt(), CURRENT.endAt()))
				.thenReturn(List.of(occurrence));

		var result = read();

		assertThat(result.state()).isEqualTo("INCOMPLETE");
		assertThat(result.summary()).isNull();
		assertThat(result.selfReport().source().selfReportId()).isEqualTo(REPORT);
		assertThat(result.selfReport().source().sourceRevision()).isEqualTo(4);
		assertThat(result.selfReport().source().currentPeriod()).isEqualTo(CURRENT);
		assertThat(result.occurrences()).singleElement().satisfies(item -> {
			assertThat(item.occurrenceId()).isEqualTo(occurrence.occurrenceId());
			assertThat(item.occurrenceVersion()).isEqualTo(7);
			assertThat(item.actionId()).isEqualTo(ACTION);
			assertThat(item.actionVersion()).isEqualTo(3);
			assertThat(item.supportPlanId()).isEqualTo(PLAN);
			assertThat(item.sourcePlanVersion()).isEqualTo(2);
			assertThat(item.sourceContentVersion()).isNull();
			assertThat(item.engagementUpdatedAt()).isEqualTo(NOW.minusSeconds(600));
		});
		assertThat(mapper.writeValueAsString(result)).doesNotContain("private optional reflection");
		verifyNoInteractions(journal);
	}

	@Test
	void leavesFingerprintStableWhenOnlyObservationTimeAdvances() {
		var first = read();
		when(clock.instant()).thenReturn(NOW.plusSeconds(120));
		var second = read();

		assertThat(second.localToken()).isEqualTo(first.localToken());
		assertThat(second.sourceToken()).isEqualTo(first.sourceToken());
		assertThat(service.localToken(USER, first)).isEqualTo(first.localToken());
	}

	@Test
	void invalidatesLocalSnapshotOnApprovalWithdrawalOrOccurrenceRevisionChange() {
		var sourceId = UUID.randomUUID();
		when(evidence.findReusableEngagement(USER, CURRENT.startAt(), CURRENT.endAt()))
				.thenReturn(List.of(engagement(sourceId, 1, "LOW_ENERGY")));
		var first = read();
		when(evidence.findReusableEngagement(USER, CURRENT.startAt(), CURRENT.endAt()))
				.thenReturn(List.of(engagement(sourceId, 2, "NOT_A_GOOD_FIT")));
		assertThat(service.localToken(USER, first)).isNotEqualTo(first.localToken());
		when(evidence.findReusableEngagement(USER, CURRENT.startAt(), CURRENT.endAt())).thenReturn(List.of());
		assertThat(service.localToken(USER, first)).isNotEqualTo(first.localToken());
		assertThat(read().occurrences()).isEmpty();
	}

	@Test
	void invalidatesLocalSnapshotWhenANewSummaryBecomesCurrent() {
		var first = read();
		doReturn(summary(false)).when(summaries).current(USER);

		assertThat(service.localToken(USER, first)).isNotEqualTo(first.localToken());
	}

	@Test
	void masksDeletedSelfReportAndInvalidatesPriorSelectionWithoutChangingSavedSummary() {
		var summary = summary(true);
		stored(summary);
		when(reports.find(USER, REPORT)).thenReturn(Optional.of(report(0, false)));
		var first = read();
		when(reports.find(USER, REPORT)).thenReturn(Optional.of(report(1, true)));

		var deleted = read();

		assertThat(deleted.state()).isEqualTo("STALE");
		assertThat(deleted.reasonCode()).isEqualTo("SOURCE_DELETED");
		assertThat(deleted.selfReport().state()).isEqualTo("UNAVAILABLE");
		assertThat(deleted.summary().selfReportedExperience().source()).isNull();
		assertThat(deleted.sourceToken()).isNotEqualTo(first.sourceToken());
		assertThat(service.localToken(USER, first)).isNotEqualTo(first.localToken());
		assertThat(summary.selfReportedExperience().source().currentExperience()).isEqualTo("MORE_DIFFICULT");
	}

	@Test
	void editedSelfReportIsShownWithExactRevisionAndRequiresFreshSummary() {
		stored(summary(true));
		when(reports.find(USER, REPORT)).thenReturn(Optional.of(report(8, false)));

		var edited = read();

		assertThat(edited.state()).isEqualTo("STALE");
		assertThat(edited.reasonCode()).isEqualTo("SOURCE_REVISION_CHANGED");
		assertThat(edited.selfReport().source().sourceRevision()).isEqualTo(8);
	}

	@Test
	void masksWithdrawnJournalEvidenceAndKeepsOtherDimensionsUsable() {
		var original = summary(false);
		var analysis = UUID.randomUUID();
		var journalSnapshot = new JournalDimension("AVAILABLE", null, UUID.randomUUID(), analysis, List.of(),
				List.of("work-pressure"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
		var withJournal = new ReassessmentSummaryView(original.summaryId(), original.summaryVersion(), original.composedAt(),
				PREVIOUS, CURRENT, original.screening(), journalSnapshot, original.supportPlanEngagement(),
				original.selfReportedExperience(), original.activityReflection(), null, original.disclaimerCode());
		stored(withJournal);
		when(journal.read(eq(USER), eq(analysis), eq(PREVIOUS), eq(CURRENT), eq("token"), any()))
				.thenReturn(new JournalLongitudinalClient.Projection("UNAVAILABLE", "CONSENT_REVOKED", null));

		var result = read();

		assertThat(result.state()).isEqualTo("AVAILABLE");
		assertThat(result.summary().journalContext().state()).isEqualTo("UNAVAILABLE");
		assertThat(result.summary().journalContext().unavailableReason()).isEqualTo("CONSENT_REVOKED");
		assertThat(result.summary().journalContext().sourceJournalRevisions()).isEmpty();
		assertThat(result.summary().journalContext().contextSignals()).isEmpty();
		assertThat(result.summary().screening()).isEqualTo(withJournal.screening());
		assertThat(withJournal.journalContext().contextSignals()).containsExactly("work-pressure");
	}

	@Test
	void removesUnapprovedHistoricalReflectionAndPreservesOnlyCurrentApprovedCodedHelpfulness() throws Exception {
		stored(summary(false));
		var occurrence = engagement(UUID.randomUUID(), 2, null);
		when(evidence.findReusableEngagement(USER, CURRENT.startAt(), CURRENT.endAt()))
				.thenReturn(List.of(occurrence));

		var result = read();

		assertThat(result.summary().activityReflection().sources()).singleElement().satisfies(item -> {
			assertThat(item.occurrenceId()).isEqualTo(occurrence.occurrenceId());
			assertThat(item.helpfulness()).isEqualTo("HELPFUL");
			assertThat(item.reflection()).isNull();
		});
		assertThat(mapper.writeValueAsString(result)).doesNotContain("private optional reflection");
		when(evidence.findReusableEngagement(USER, CURRENT.startAt(), CURRENT.endAt())).thenReturn(List.of());
		assertThat(read().summary().activityReflection().sources()).isEmpty();
	}

	@Test
	void rejectsForeignRequestedSummaryAndMarksVoidedScreeningStale() {
		UUID foreign = UUID.randomUUID();
		when(summaries.get(USER, foreign)).thenThrow(noSummary());
		assertThatThrownBy(() -> service.read(USER, foreign, "token", UUID.randomUUID()))
				.isInstanceOf(ApiException.class).extracting("code").isEqualTo("REASSESSMENT_SUMMARY_NOT_FOUND");
		stored(summary(false));
		var source = assessment(PHQ9, "PHQ9");
		when(evidence.findAssessment(USER, PHQ9)).thenReturn(Optional.of(new AssessmentEvidence(PHQ9,
				source.submittedAt(), NOW, source.instrument(), source.questionnaireVersion(), source.totalScore(),
				source.screeningLevel(), source.scoringVersion())));

		assertThat(read().state()).isEqualTo("STALE");
		assertThat(read().reasonCode()).isEqualTo("SOURCE_DELETED");
	}

	private AdaptiveReviewEvidenceService.Context read() {
		return service.read(USER, null, "token", UUID.randomUUID());
	}

	private void stored(ReassessmentSummaryView summary) {
		doReturn(summary).when(summaries).current(USER);
		when(summaries.get(USER, summary.summaryId())).thenReturn(summary);
	}

	private ReassessmentSummaryView summary(boolean selfReport) {
		var screening = new ScreeningDimension("INSUFFICIENT_DATA", List.of(trend(PHQ9, "PHQ9"), trend(GAD7, "GAD7")));
		var journalSnapshot = new JournalDimension("UNAVAILABLE", "NOT_PROVIDED", null, null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
		var counts = new EngagementPeriod(0, 0);
		var report = selfReport ? new SelfReportedExperienceDimension("AVAILABLE", null, new ExplicitSelfReport(
				REPORT, "reassessment-self-report-v1", 0, CURRENT, "MORE_DIFFICULT", "Helpful context", null, NOW, NOW))
				: new SelfReportedExperienceDimension("INSUFFICIENT_DATA", "NOT_PROVIDED", null);
		return new ReassessmentSummaryView(UUID.randomUUID(), "reassessment-summary-v2", NOW, PREVIOUS, CURRENT,
				screening, journalSnapshot, new EngagementDimension("INSUFFICIENT_DATA", counts, counts, List.of()),
				report, new ReflectionDimension("INSUFFICIENT_DATA", List.of(new ReflectionSource(UUID.randomUUID(),
						"CURRENT", "HELPFUL", "private optional reflection", NOW))), null,
				"FOUR_DIMENSIONS_NOT_COMBINED");
	}

	private ScreeningTrend trend(UUID id, String instrument) {
		return new ScreeningTrend(instrument, "INSUFFICIENT_DATA", "scoring-v1", null,
				new ScreeningPoint(id, "questionnaire-v1", NOW.minusSeconds(100), 4, ScreeningLevel.MINIMAL),
				null, "INSUFFICIENT_DATA");
	}

	private AssessmentEvidence assessment(UUID id, String instrument) {
		return new AssessmentEvidence(id, NOW.minusSeconds(100), null, instrument, "questionnaire-v1", 4,
				ScreeningLevel.MINIMAL, "scoring-v1");
	}

	private StoredSelfReport report(long version, boolean deleted) {
		return new StoredSelfReport(REPORT, USER, "key", "hash", "reassessment-self-report-v1", CURRENT.startAt(),
				CURRENT.endAt(), deleted ? null : "MORE_DIFFICULT", deleted ? null : "Helpful context", null,
				version, NOW.minusSeconds(1000), NOW.minusSeconds(500), deleted ? NOW : null);
	}

	private EngagementEvidence engagement(UUID id, long version, String barrier) {
		return new EngagementEvidence(id, PLAN, 2, null, null, null, CURRENT.startAt().plusSeconds(3600),
				barrier == null ? "COMPLETED" : "SKIPPED", barrier == null ? "HELPFUL" : null, barrier,
				"private optional reflection", NOW.minusSeconds(600), ACTION, 3L, version);
	}

	private ApiException noSummary() {
		return new ApiException(HttpStatus.NOT_FOUND, "REASSESSMENT_SUMMARY_NOT_FOUND", "Summary not found");
	}
}
