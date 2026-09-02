package com.mentalbridge.care.assessment;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

import com.mentalbridge.care.shared.ApiException;

@RestController
@RequestMapping("/api/v1")
@Validated
public class AssessmentController {

	private final AssessmentService assessments;

	public AssessmentController(AssessmentService assessments) {
		this.assessments = assessments;
	}

	@PostMapping("/anonymous-assessment-sessions")
	ResponseEntity<AnonymousSessionResponse> createAnonymousSession() {
		var created = assessments.createAnonymousSession();
		return ResponseEntity.created(URI.create("/api/v1/anonymous-assessment-sessions/" + created.sessionId()))
				.body(new AnonymousSessionResponse(created.sessionId(), created.sessionToken(), created.expiresAt()));
	}

	@PostMapping("/assessments")
	ResponseEntity<AssessmentResponse> submitAuthenticated(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String idempotencyKey,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody AssessmentSubmissionRequest request) {
		var submitted = assessments.submitAuthenticated(subject(jwt), idempotencyKey, correlationId(correlationId),
				request.command());
		return ResponseEntity.created(URI.create("/api/v1/assessments/" + submitted.assessmentId()))
				.body(AssessmentResponse.from(submitted));
	}

	@GetMapping("/assessments/{assessmentId}")
	AssessmentResponse getAuthenticated(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID assessmentId) {
		return AssessmentResponse.from(assessments.getAuthenticated(subject(jwt), assessmentId));
	}

	@GetMapping("/assessments")
	AssessmentHistoryResponse history(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) @Size(max = 256) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		var page = assessments.history(subject(jwt), cursor, limit);
		return new AssessmentHistoryResponse(page.items().stream().map(AssessmentSummaryResponse::from).toList(),
				page.nextCursor(), page.hasMore());
	}

	@PostMapping("/anonymous-assessment-sessions/{sessionId}/assessments")
	ResponseEntity<AnonymousAssessmentResponse> submitAnonymous(@PathVariable UUID sessionId,
			@RequestHeader("X-Anonymous-Session-Token") String sessionToken,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String idempotencyKey,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody AssessmentSubmissionRequest request) {
		var submitted = assessments.submitAnonymous(sessionId, sessionToken, idempotencyKey,
				correlationId(correlationId), request.command());
		return ResponseEntity.created(URI.create("/api/v1/anonymous-assessment-sessions/" + sessionId
				+ "/assessments/" + submitted.assessmentId())).body(AnonymousAssessmentResponse.from(submitted));
	}

	@GetMapping("/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}")
	AnonymousAssessmentResponse getAnonymous(@PathVariable UUID sessionId, @PathVariable UUID assessmentId,
			@RequestHeader("X-Anonymous-Session-Token") String sessionToken) {
		return AnonymousAssessmentResponse.from(assessments.getAnonymous(sessionId, sessionToken, assessmentId));
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

	private UUID correlationId(UUID supplied) {
		return supplied == null ? UUID.randomUUID() : supplied;
	}

	public record AssessmentSubmissionRequest(@NotNull UUID questionnaireDefinitionId,
			@NotNull @Size(max = 64) String privacyPolicyVersion,
			@jakarta.validation.constraints.AssertTrue boolean privacyDisclosureAcknowledged,
			@NotEmpty @Size(max = 32) List<@Valid AssessmentAnswerRequest> answers) {

		AssessmentService.SubmissionCommand command() {
			return new AssessmentService.SubmissionCommand(questionnaireDefinitionId, privacyPolicyVersion,
					privacyDisclosureAcknowledged,
					answers.stream().map(answer -> new AssessmentService.AnswerCommand(answer.questionId(), answer.value()))
							.toList());
		}

		@Override
		public String toString() {
			return "AssessmentSubmissionRequest[questionnaireDefinitionId=" + questionnaireDefinitionId
					+ ", privacyPolicyVersion=" + privacyPolicyVersion
					+ ", answers=[REDACTED], answerCount=" + (answers == null ? 0 : answers.size()) + "]";
		}
	}

	public record AssessmentAnswerRequest(@NotNull UUID questionId, @NotNull @Min(0) @Max(3) Integer value) {
	}

	public record AnonymousSessionResponse(UUID sessionId, String sessionToken, Instant expiresAt) {

		@Override
		public String toString() {
			return "AnonymousSessionResponse[sessionId=" + sessionId + ", sessionToken=[REDACTED], expiresAt="
					+ expiresAt + "]";
		}
	}

	public record AssessmentResponse(UUID assessmentId, UUID questionnaireDefinitionId, String instrument,
			String questionnaireVersion, String privacyPolicyVersion, Instant submittedAt, Instant voidedAt,
			ResultResponse result) {

		static AssessmentResponse from(AssessmentService.AssessmentView view) {
			return new AssessmentResponse(view.assessmentId(), view.questionnaireDefinitionId(), view.instrument(),
					view.questionnaireVersion(), view.privacyPolicyVersion(), view.submittedAt(), view.voidedAt(),
					ResultResponse.from(view.result()));
		}
	}

	public record AnonymousAssessmentResponse(UUID assessmentId, UUID questionnaireDefinitionId, String instrument,
			String questionnaireVersion, String privacyPolicyVersion, Instant submittedAt, Instant voidedAt, ResultResponse result,
			Instant expiresAt) {

		static AnonymousAssessmentResponse from(AssessmentService.AssessmentView view) {
			return new AnonymousAssessmentResponse(view.assessmentId(), view.questionnaireDefinitionId(),
					view.instrument(), view.questionnaireVersion(), view.privacyPolicyVersion(), view.submittedAt(), view.voidedAt(),
					ResultResponse.from(view.result()), view.expiresAt());
		}
	}

	public record AssessmentSummaryResponse(UUID assessmentId, UUID questionnaireDefinitionId, String instrument,
			String questionnaireVersion, String privacyPolicyVersion, Instant submittedAt, ResultResponse result) {
		static AssessmentSummaryResponse from(AssessmentService.AssessmentView view) {
			return new AssessmentSummaryResponse(view.assessmentId(), view.questionnaireDefinitionId(), view.instrument(),
					view.questionnaireVersion(), view.privacyPolicyVersion(), view.submittedAt(), ResultResponse.from(view.result()));
		}
	}

	public record AssessmentHistoryResponse(List<AssessmentSummaryResponse> items, String nextCursor, boolean hasMore) {
	}

	public record ResultResponse(int totalScore, ScreeningLevel screeningLevel, String scoringVersion,
			SafetyStatus safetyStatus, String safetyPolicyVersion, String disclaimerCode) {

		static ResultResponse from(AssessmentService.ResultView view) {
			return new ResultResponse(view.totalScore(), view.screeningLevel(), view.scoringVersion(),
					view.safetyStatus(), view.safetyPolicyVersion(), view.disclaimerCode());
		}
	}
}
