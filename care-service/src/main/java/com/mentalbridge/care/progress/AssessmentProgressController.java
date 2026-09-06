package com.mentalbridge.care.progress;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.shared.ApiException;

@RestController
@RequestMapping("/api/v1/assessments")
public class AssessmentProgressController {

	private final AssessmentProgressService progress;

	public AssessmentProgressController(AssessmentProgressService progress) {
		this.progress = progress;
	}

	@GetMapping("/{assessmentId}/progress")
	AssessmentProgressResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID assessmentId) {
		return AssessmentProgressResponse.from(progress.get(subject(jwt), assessmentId));
	}

	private UUID subject(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}

	public record AssessmentProgressResponse(String instrument, String scoringVersion, ProgressPointResponse previous,
			ProgressPointResponse current, int rawDelta, ScoreDirection scoreDirection,
			BandTransitionResponse bandTransition, String elapsedDuration) {

		static AssessmentProgressResponse from(AssessmentProgressService.ProgressView view) {
			return new AssessmentProgressResponse(view.instrument(), view.scoringVersion(),
					ProgressPointResponse.from(view.previous()), ProgressPointResponse.from(view.current()), view.rawDelta(),
					view.scoreDirection(), BandTransitionResponse.from(view.bandTransition()), view.elapsedDuration());
		}
	}

	public record ProgressPointResponse(UUID assessmentId, String questionnaireVersion, Instant submittedAt,
			int totalScore, ScreeningLevel screeningLevel) {

		static ProgressPointResponse from(AssessmentProgressService.ProgressPoint point) {
			return new ProgressPointResponse(point.assessmentId(), point.questionnaireVersion(), point.submittedAt(),
					point.totalScore(), point.screeningLevel());
		}
	}

	public record BandTransitionResponse(ScreeningLevel previous, ScreeningLevel current) {

		static BandTransitionResponse from(AssessmentProgressService.BandTransition transition) {
			return new BandTransitionResponse(transition.previous(), transition.current());
		}
	}
}
