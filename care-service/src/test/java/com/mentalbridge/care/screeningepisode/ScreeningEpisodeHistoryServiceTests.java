package com.mentalbridge.care.screeningepisode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.CombinedScreeningMeaningService;
import com.mentalbridge.care.support.CombinedScreeningMeaningService.CombinedMeaningView;

class ScreeningEpisodeHistoryServiceTests {

	private final ScreeningEpisodeRepository episodes = mock(ScreeningEpisodeRepository.class);
	private final CombinedScreeningMeaningService meanings = mock(CombinedScreeningMeaningService.class);
	private final ScreeningEpisodeHistoryService service = new ScreeningEpisodeHistoryService(episodes, meanings);

	@Test
	void returnsEpisodesInExactRecordedOrderAndUsesAnOpaqueKeysetCursor() {
		var userId = UUID.randomUUID();
		var first = completed(userId, "REASSESSMENT", Instant.parse("2026-10-14T09:00:00Z"));
		var second = completed(userId, "INITIAL_CHECK", Instant.parse("2026-10-13T09:00:00Z"));
		var lookahead = completed(userId, "REASSESSMENT", Instant.parse("2026-10-12T09:00:00Z"));
		when(episodes.findHistory(eq(userId), any()))
				.thenReturn(List.of(first, second, lookahead));
		var firstMeaning = meaning(first);
		var secondMeaning = meaning(second);
		when(meanings.get(userId, first.presentationEvaluationId())).thenReturn(firstMeaning);
		when(meanings.get(userId, second.presentationEvaluationId())).thenReturn(secondMeaning);

		var page = service.history(userId, 2, null);

		assertThat(page.hasMore()).isTrue();
		assertThat(page.nextCursor()).isNotBlank();
		assertThat(page.items()).extracting(ScreeningEpisodeHistoryService.HistoryItem::episodeId)
				.containsExactly(first.id(), second.id());
		assertThat(page.items()).allSatisfy(item -> {
			assertThat(item.status()).isEqualTo("COMPLETED");
			assertThat(item.meaningState()).isEqualTo("AVAILABLE");
			assertThat(item.meaning()).isNotNull();
			assertThat(item.evidence()).extracting(ScreeningEpisodeHistoryService.HistoryEvidence::state)
					.containsExactly("AVAILABLE", "AVAILABLE");
		});

		when(episodes.findHistoryAfter(eq(userId), eq(second.createdAt()), eq(second.id()), any(Pageable.class)))
				.thenReturn(List.of());
		var next = service.history(userId, 2, page.nextCursor());
		assertThat(next.items()).isEmpty();
		verify(episodes).findHistoryAfter(eq(userId), eq(second.createdAt()), eq(second.id()), any(Pageable.class));
	}

	@Test
	void retainsLegacyEpisodeAsUnavailableWithoutSelectingOtherEvidence() {
		var userId = UUID.randomUUID();
		var episode = completed(userId, "INITIAL_CHECK", Instant.parse("2026-10-13T09:00:00Z"));
		when(episodes.findHistory(eq(userId), any()))
				.thenReturn(List.of(episode));
		when(meanings.get(userId, episode.presentationEvaluationId())).thenThrow(new ApiException(
				HttpStatus.SERVICE_UNAVAILABLE, "COMBINED_SCREENING_MEANING_UNAVAILABLE", "unavailable"));

		var page = service.history(userId, 10, null);

		assertThat(page.items()).singleElement().satisfies(item -> {
			assertThat(item.episodeId()).isEqualTo(episode.id());
			assertThat(item.presentationEvaluationId()).isEqualTo(episode.presentationEvaluationId());
			assertThat(item.meaningState()).isEqualTo("UNAVAILABLE");
			assertThat(item.meaning()).isNull();
		});
	}

	@Test
	void exposesOnlyTheExactSavedAssessmentForAnIncompleteEpisode() {
		var userId = UUID.randomUUID();
		var startedAt = Instant.parse("2026-10-15T08:00:00Z");
		var episode = new ScreeningEpisodeEntity(UUID.randomUUID(), userId, "REASSESSMENT", startedAt);
		var phq9Id = UUID.randomUUID();
		episode.attachAssessment("PHQ9", phq9Id, startedAt.plusSeconds(60));
		var phq9 = evidence(phq9Id, "PHQ9", 8, "MILD", "NEGATIVE_SAFETY_SCREEN");
		when(episodes.findHistory(eq(userId), any())).thenReturn(List.of(episode));
		when(episodes.findHistoryEvidence(userId, List.of(phq9Id))).thenReturn(List.of(phq9));

		var page = service.history(userId, 10, null);

		assertThat(page.items()).singleElement().satisfies(item -> {
			assertThat(item.status()).isEqualTo("IN_PROGRESS");
			assertThat(item.completedAt()).isNull();
			assertThat(item.presentationEvaluationId()).isNull();
			assertThat(item.meaningState()).isEqualTo("INCOMPLETE");
			assertThat(item.meaning()).isNull();
			assertThat(item.evidence()).containsExactly(
					new ScreeningEpisodeHistoryService.HistoryEvidence(
							"PHQ9", "AVAILABLE", phq9Id, 8, "MILD", "NEGATIVE_SAFETY_SCREEN"),
					new ScreeningEpisodeHistoryService.HistoryEvidence(
							"GAD7", "MISSING", null, null, null, null));
		});
		verify(episodes).findHistoryEvidence(userId, List.of(phq9Id));
	}

	@Test
	void exposesExactReadyEvidenceWithoutResolvingACombinedMeaning() {
		var userId = UUID.randomUUID();
		var startedAt = Instant.parse("2026-10-15T08:00:00Z");
		var episode = new ScreeningEpisodeEntity(UUID.randomUUID(), userId, "INITIAL_CHECK", startedAt);
		var phq9Id = UUID.randomUUID();
		var gad7Id = UUID.randomUUID();
		episode.attachAssessment("PHQ9", phq9Id, startedAt.plusSeconds(60));
		episode.attachAssessment("GAD7", gad7Id, startedAt.plusSeconds(120));
		var phq9 = evidence(phq9Id, "PHQ9", 12, "MODERATE", "NEGATIVE_SAFETY_SCREEN");
		var gad7 = evidence(gad7Id, "GAD7", 16, "SEVERE", "NOT_APPLICABLE");
		when(episodes.findHistory(eq(userId), any())).thenReturn(List.of(episode));
		when(episodes.findHistoryEvidence(userId, List.of(phq9Id, gad7Id))).thenReturn(List.of(phq9, gad7));

		var page = service.history(userId, 10, null);

		assertThat(page.items()).singleElement().satisfies(item -> {
			assertThat(item.status()).isEqualTo("READY");
			assertThat(item.presentationEvaluationId()).isNull();
			assertThat(item.meaningState()).isEqualTo("INCOMPLETE");
			assertThat(item.meaning()).isNull();
			assertThat(item.evidence()).containsExactly(
					new ScreeningEpisodeHistoryService.HistoryEvidence(
							"PHQ9", "AVAILABLE", phq9Id, 12, "MODERATE", "NEGATIVE_SAFETY_SCREEN"),
					new ScreeningEpisodeHistoryService.HistoryEvidence(
							"GAD7", "AVAILABLE", gad7Id, 16, "SEVERE", "NOT_APPLICABLE"));
		});
		verifyNoInteractions(meanings);
	}

	@Test
	void marksInvalidVoidedAndWrongInstrumentEvidenceUnavailable() {
		var userId = UUID.randomUUID();
		var startedAt = Instant.parse("2026-10-15T08:00:00Z");
		var first = ready(userId, "INITIAL_CHECK", startedAt);
		var second = ready(userId, "REASSESSMENT", startedAt.minusSeconds(300));
		var wrongInstrument = evidence(first.phq9AssessmentId(), "GAD7", 8, "MILD", "NOT_APPLICABLE");
		var invalidScore = evidence(first.gad7AssessmentId(), "GAD7", 22, "SEVERE", "NOT_APPLICABLE");
		var voided = evidence(second.phq9AssessmentId(), "PHQ9", 8, "MILD", "NEGATIVE_SAFETY_SCREEN");
		when(voided.getVoidedAt()).thenReturn(startedAt.minusSeconds(120));
		var valid = evidence(second.gad7AssessmentId(), "GAD7", 7, "MILD", "NOT_APPLICABLE");
		when(episodes.findHistory(eq(userId), any())).thenReturn(List.of(first, second));
		when(episodes.findHistoryEvidence(userId, List.of(
				first.phq9AssessmentId(), first.gad7AssessmentId(),
				second.phq9AssessmentId(), second.gad7AssessmentId())))
				.thenReturn(List.of(wrongInstrument, invalidScore, voided, valid));

		var page = service.history(userId, 10, null);

		assertThat(page.items()).hasSize(2);
		assertThat(page.items().getFirst().evidence()).containsExactly(
				new ScreeningEpisodeHistoryService.HistoryEvidence(
						"PHQ9", "UNAVAILABLE", first.phq9AssessmentId(), null, null, null),
				new ScreeningEpisodeHistoryService.HistoryEvidence(
						"GAD7", "UNAVAILABLE", first.gad7AssessmentId(), null, null, null));
		assertThat(page.items().getLast().evidence()).containsExactly(
				new ScreeningEpisodeHistoryService.HistoryEvidence(
						"PHQ9", "UNAVAILABLE", second.phq9AssessmentId(), null, null, null),
				new ScreeningEpisodeHistoryService.HistoryEvidence(
						"GAD7", "AVAILABLE", second.gad7AssessmentId(), 7, "MILD", "NOT_APPLICABLE"));
		verifyNoInteractions(meanings);
	}

	@Test
	void preservesPositivePhqSafetyAndDoesNotInferSafetyFromSeverity() {
		var userId = UUID.randomUUID();
		var startedAt = Instant.parse("2026-10-15T08:00:00Z");
		var positiveMinimal = ready(userId, "INITIAL_CHECK", startedAt);
		var negativeSevere = ready(userId, "REASSESSMENT", startedAt.minusSeconds(300));
		var positiveMinimalPhq9 = evidence(positiveMinimal.phq9AssessmentId(), "PHQ9", 2, "MINIMAL",
				"POSITIVE_SAFETY_SCREEN");
		var positiveMinimalGad7 = evidence(positiveMinimal.gad7AssessmentId(), "GAD7", 1, "MINIMAL",
				"NOT_APPLICABLE");
		var negativeSeverePhq9 = evidence(negativeSevere.phq9AssessmentId(), "PHQ9", 22, "SEVERE",
				"NEGATIVE_SAFETY_SCREEN");
		var negativeSevereGad7 = evidence(negativeSevere.gad7AssessmentId(), "GAD7", 16, "SEVERE",
				"NOT_APPLICABLE");
		when(episodes.findHistory(eq(userId), any())).thenReturn(List.of(positiveMinimal, negativeSevere));
		when(episodes.findHistoryEvidence(userId, List.of(
				positiveMinimal.phq9AssessmentId(), positiveMinimal.gad7AssessmentId(),
				negativeSevere.phq9AssessmentId(), negativeSevere.gad7AssessmentId())))
				.thenReturn(List.of(positiveMinimalPhq9, positiveMinimalGad7,
						negativeSeverePhq9, negativeSevereGad7));

		var page = service.history(userId, 10, null);

		assertThat(page.items().getFirst().evidence().getFirst().safetyStatus())
				.isEqualTo("POSITIVE_SAFETY_SCREEN");
		assertThat(page.items().getLast().evidence().getFirst()).satisfies(phq9 -> {
			assertThat(phq9.screeningLevel()).isEqualTo("SEVERE");
			assertThat(phq9.safetyStatus()).isEqualTo("NEGATIVE_SAFETY_SCREEN");
		});
		verifyNoInteractions(meanings);
	}

	@Test
	void rejectsMalformedCursorBeforeRepositoryAccess() {
		assertThatThrownBy(() -> service.history(UUID.randomUUID(), 10, "not-a-cursor"))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(error.code()).isEqualTo("INVALID_CURSOR");
				});
	}

	private ScreeningEpisodeEntity completed(UUID userId, String purpose, Instant at) {
		var episode = new ScreeningEpisodeEntity(UUID.randomUUID(), userId, purpose, at.minusSeconds(60));
		episode.attachAssessment("PHQ9", UUID.randomUUID(), at.minusSeconds(40));
		episode.attachAssessment("GAD7", UUID.randomUUID(), at.minusSeconds(20));
		episode.complete(UUID.randomUUID(), UUID.randomUUID(), at);
		return episode;
	}

	private ScreeningEpisodeEntity ready(UUID userId, String purpose, Instant at) {
		var episode = new ScreeningEpisodeEntity(UUID.randomUUID(), userId, purpose, at);
		episode.attachAssessment("PHQ9", UUID.randomUUID(), at.plusSeconds(20));
		episode.attachAssessment("GAD7", UUID.randomUUID(), at.plusSeconds(40));
		return episode;
	}

	private CombinedMeaningView meaning(ScreeningEpisodeEntity episode) {
		var view = mock(CombinedMeaningView.class);
		when(view.episodeId()).thenReturn(episode.id());
		when(view.episodePurpose()).thenReturn(episode.purpose());
		when(view.episodeCompletedAt()).thenReturn(episode.completedAt());
		when(view.evidence()).thenReturn(List.of(
				new CombinedScreeningMeaningService.EvidenceView(episode.phq9AssessmentId(), "PHQ9", "phq9-v1",
						"phq9-score-v1", 0, ScreeningLevel.MINIMAL, SafetyStatus.NEGATIVE_SAFETY_SCREEN),
				new CombinedScreeningMeaningService.EvidenceView(episode.gad7AssessmentId(), "GAD7", "gad7-v1",
						"gad7-score-v1", 0, ScreeningLevel.MINIMAL, SafetyStatus.NOT_APPLICABLE)));
		return view;
	}

	private ScreeningEpisodeRepository.HistoryEvidenceRow evidence(UUID assessmentId, String instrument,
			int score, String level, String safetyStatus) {
		var row = mock(ScreeningEpisodeRepository.HistoryEvidenceRow.class);
		when(row.getAssessmentId()).thenReturn(assessmentId);
		when(row.getInstrument()).thenReturn(instrument);
		when(row.getTotalScore()).thenReturn(score);
		when(row.getScreeningLevel()).thenReturn(level);
		when(row.getSafetyStatus()).thenReturn(safetyStatus);
		return row;
	}
}
