package com.mentalbridge.care.screeningepisode;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.assessment.AssessmentController.AssessmentAnswerRequest;
import com.mentalbridge.care.assessment.AssessmentController.AssessmentResponse;
import com.mentalbridge.care.assessment.AssessmentService;
import com.mentalbridge.care.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/screening-episodes")
@Validated
public class ScreeningEpisodeController {

	private final ScreeningEpisodeService episodes;

	public ScreeningEpisodeController(ScreeningEpisodeService episodes) {
		this.episodes = episodes;
	}

	@PostMapping
	ResponseEntity<ScreeningEpisodeService.EpisodeView> start(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody StartRequest request) {
		var episode = episodes.start(subject(jwt), request.purpose());
		return ResponseEntity.created(URI.create("/api/v1/screening-episodes/" + episode.episodeId())).body(episode);
	}

	@GetMapping("/current")
	ScreeningEpisodeService.EpisodeView current(@AuthenticationPrincipal Jwt jwt,
			@RequestParam @Pattern(regexp = "^(INITIAL_CHECK|REASSESSMENT)$") String purpose) {
		return episodes.current(subject(jwt), purpose);
	}

	@PostMapping("/{episodeId}/assessments/{instrument}")
	ResponseEntity<AssessmentResponse> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID episodeId,
			@PathVariable @Pattern(regexp = "^(PHQ9|GAD7)$") String instrument,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody AssessmentRequest request) {
		var assessment = episodes.submitAssessment(subject(jwt), episodeId, instrument, key,
				correlationId == null ? UUID.randomUUID() : correlationId, request.command());
		return ResponseEntity.created(URI.create("/api/v1/assessments/" + assessment.assessmentId()))
				.body(AssessmentResponse.from(assessment));
	}

	@PostMapping("/{episodeId}/support-evaluation")
	ResponseEntity<ScreeningEpisodeService.EvaluationOutcome> evaluate(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID episodeId,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		var outcome = episodes.evaluate(subject(jwt), episodeId,
				correlationId == null ? UUID.randomUUID() : correlationId);
		return ResponseEntity.created(URI.create("/api/v1/screening-episodes/" + episodeId)).body(outcome);
	}

	private UUID subject(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}

	public record StartRequest(@NotNull @Pattern(regexp = "^(INITIAL_CHECK|REASSESSMENT)$") String purpose) { }

	public record AssessmentRequest(@NotNull UUID questionnaireDefinitionId,
			@NotNull @Size(max = 64) String privacyPolicyVersion,
			@AssertTrue boolean privacyDisclosureAcknowledged,
			@NotEmpty @Size(max = 32) List<@Valid AssessmentAnswerRequest> answers) {

		AssessmentService.SubmissionCommand command() {
			return new AssessmentService.SubmissionCommand(questionnaireDefinitionId, privacyPolicyVersion,
					privacyDisclosureAcknowledged, answers.stream()
							.map(answer -> new AssessmentService.AnswerCommand(answer.questionId(), answer.value()))
							.toList());
		}
	}
}
