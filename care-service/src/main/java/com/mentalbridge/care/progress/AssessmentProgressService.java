package com.mentalbridge.care.progress;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.progress.AssessmentProgressRepository.AssessmentEvidence;
import com.mentalbridge.care.shared.ApiException;

@Service
public class AssessmentProgressService {

	private final AssessmentProgressRepository progress;

	public AssessmentProgressService(AssessmentProgressRepository progress) {
		this.progress = progress;
	}

	@Transactional(readOnly = true)
	public ProgressView get(UUID userId, UUID assessmentId) {
		var current = progress.findOwnedCurrent(userId, assessmentId).orElseThrow(
				() -> new ApiException(HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND", "Assessment was not found"));
		if (!current.comparableCurrent()) throw insufficientComparableData();

		var previous = progress.findImmediatelyPreviousCompatible(userId, current)
				.orElseThrow(this::insufficientComparableData);
		var rawDelta = current.totalScore() - previous.totalScore();
		return new ProgressView(current.instrument(), current.scoringVersion(), point(previous), point(current), rawDelta,
				ScoreDirection.fromDelta(rawDelta),
				new BandTransition(previous.screeningLevel(), current.screeningLevel()),
				Duration.between(previous.submittedAt(), current.submittedAt()).toString());
	}

	private ProgressPoint point(AssessmentEvidence evidence) {
		return new ProgressPoint(evidence.assessmentId(), evidence.questionnaireVersion(), evidence.submittedAt(),
				evidence.totalScore(), evidence.screeningLevel());
	}

	private ApiException insufficientComparableData() {
		return new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_COMPARABLE_DATA",
				"Comparable assessment data is unavailable");
	}

	public record ProgressView(String instrument, String scoringVersion, ProgressPoint previous, ProgressPoint current,
			int rawDelta, ScoreDirection scoreDirection, BandTransition bandTransition, String elapsedDuration) {
	}

	public record ProgressPoint(UUID assessmentId, String questionnaireVersion, Instant submittedAt, int totalScore,
			ScreeningLevel screeningLevel) {
	}

	public record BandTransition(ScreeningLevel previous, ScreeningLevel current) {
	}
}
