package com.mentalbridge.care.screeningepisode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.CombinedScreeningMeaningService;
import com.mentalbridge.care.support.CombinedScreeningMeaningService.CombinedMeaningView;

@Service
public class ScreeningEpisodeHistoryService {

	private final ScreeningEpisodeRepository episodes;
	private final CombinedScreeningMeaningService meanings;

	public ScreeningEpisodeHistoryService(ScreeningEpisodeRepository episodes,
			CombinedScreeningMeaningService meanings) {
		this.episodes = episodes;
		this.meanings = meanings;
	}

	@Transactional(readOnly = true)
	public HistoryPage history(UUID userId, int limit, String cursor) {
		var decoded = decode(cursor);
		var page = PageRequest.of(0, limit + 1);
		var rows = decoded == null
				? episodes.findHistory(userId, page)
				: episodes.findHistoryAfter(userId, decoded.historyAt(), decoded.id(), page);
		boolean hasMore = rows.size() > limit;
		var visibleRows = rows.stream().limit(limit).toList();
		var evidenceIds = visibleRows.stream()
				.flatMap(episode -> java.util.stream.Stream.of(
						episode.phq9AssessmentId(), episode.gad7AssessmentId()))
				.filter(java.util.Objects::nonNull)
				.distinct()
				.toList();
		Map<UUID, ScreeningEpisodeRepository.HistoryEvidenceRow> evidence = evidenceIds.isEmpty()
				? Map.of()
				: episodes.findHistoryEvidence(userId, evidenceIds).stream().collect(Collectors.toMap(
						ScreeningEpisodeRepository.HistoryEvidenceRow::getAssessmentId, Function.identity()));
		var items = visibleRows.stream().map(episode -> item(userId, episode, evidence)).toList();
		String nextCursor = hasMore ? encode(historyAt(visibleRows.getLast()), visibleRows.getLast().id()) : null;
		return new HistoryPage(items, nextCursor, hasMore);
	}

	private HistoryItem item(UUID userId, ScreeningEpisodeEntity episode,
			Map<UUID, ScreeningEpisodeRepository.HistoryEvidenceRow> evidenceRows) {
		var evidence = List.of(
				evidence(episode.phq9AssessmentId(), "PHQ9", evidenceRows),
				evidence(episode.gad7AssessmentId(), "GAD7", evidenceRows));
		if (!"COMPLETED".equals(episode.status())) {
			return new HistoryItem(episode.id(), episode.purpose(), episode.status(), episode.createdAt(),
					episode.updatedAt(), null, null, "INCOMPLETE", evidence, null);
		}
		if (episode.presentationEvaluationId() == null || episode.completedAt() == null) {
			return unavailable(episode, evidence);
		}
		try {
			var meaning = meanings.get(userId, episode.presentationEvaluationId());
			if (!episode.id().equals(meaning.episodeId())
					|| !episode.purpose().equals(meaning.episodePurpose())
					|| !episode.completedAt().equals(meaning.episodeCompletedAt())) {
				return unavailable(episode, evidence);
			}
			var exactEvidence = meaning.evidence().stream().map(result -> new HistoryEvidence(
					result.instrument(), "AVAILABLE", result.assessmentId(), result.totalScore(),
					result.screeningLevel().name(), result.safetyStatus().name())).toList();
			return new HistoryItem(episode.id(), episode.purpose(), episode.status(), episode.createdAt(),
					episode.updatedAt(), episode.completedAt(), episode.presentationEvaluationId(), "AVAILABLE",
					exactEvidence, meaning);
		}
		catch (ApiException exception) {
			if (exception.status() == HttpStatus.NOT_FOUND
					|| "COMBINED_SCREENING_MEANING_UNAVAILABLE".equals(exception.code())) {
				return unavailable(episode, evidence);
			}
			throw exception;
		}
	}

	private HistoryEvidence evidence(UUID assessmentId, String instrument,
			Map<UUID, ScreeningEpisodeRepository.HistoryEvidenceRow> rows) {
		if (assessmentId == null) {
			return new HistoryEvidence(instrument, "MISSING", null, null, null, null);
		}
		var row = rows.get(assessmentId);
		if (row == null || row.getVoidedAt() != null || !instrument.equals(row.getInstrument())
				|| !validScore(instrument, row.getTotalScore())
				|| !validLevel(instrument, row.getScreeningLevel())
				|| !validSafety(instrument, row.getSafetyStatus())) {
			return new HistoryEvidence(instrument, "UNAVAILABLE", assessmentId, null, null, null);
		}
		return new HistoryEvidence(instrument, "AVAILABLE", assessmentId, row.getTotalScore(),
				row.getScreeningLevel(), row.getSafetyStatus());
	}

	private boolean validScore(String instrument, Integer score) {
		return score != null && score >= 0 && score <= ("PHQ9".equals(instrument) ? 27 : 21);
	}

	private boolean validLevel(String instrument, String level) {
		return ("PHQ9".equals(instrument)
				? List.of("MINIMAL", "MILD", "MODERATE", "MODERATELY_SEVERE", "SEVERE")
				: List.of("MINIMAL", "MILD", "MODERATE", "SEVERE")).contains(level);
	}

	private boolean validSafety(String instrument, String safetyStatus) {
		return "PHQ9".equals(instrument)
				? List.of("NEGATIVE_SAFETY_SCREEN", "POSITIVE_SAFETY_SCREEN").contains(safetyStatus)
				: "NOT_APPLICABLE".equals(safetyStatus);
	}

	private HistoryItem unavailable(ScreeningEpisodeEntity episode, List<HistoryEvidence> evidence) {
		return new HistoryItem(episode.id(), episode.purpose(), episode.status(), episode.createdAt(),
				episode.updatedAt(), episode.completedAt(), episode.presentationEvaluationId(), "UNAVAILABLE",
				evidence, null);
	}

	private Cursor decode(String cursor) {
		if (cursor == null || cursor.isBlank()) return null;
		try {
			String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
					.split("\\|", 2);
			return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
		}
		catch (RuntimeException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
					"Screening episode history cursor is invalid");
		}
	}

	private Instant historyAt(ScreeningEpisodeEntity episode) {
		return episode.completedAt() == null ? episode.createdAt() : episode.completedAt();
	}

	private String encode(Instant historyAt, UUID id) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(
				(historyAt + "|" + id).getBytes(StandardCharsets.UTF_8));
	}

	private record Cursor(Instant historyAt, UUID id) { }

	public record HistoryEvidence(String instrument, String state, UUID assessmentId, Integer totalScore,
			String screeningLevel, String safetyStatus) { }

	public record HistoryItem(UUID episodeId, String purpose, String status, Instant startedAt, Instant updatedAt,
			Instant completedAt, UUID presentationEvaluationId, String meaningState,
			List<HistoryEvidence> evidence, CombinedMeaningView meaning) { }

	public record HistoryPage(List<HistoryItem> items, String nextCursor, boolean hasMore) { }
}
