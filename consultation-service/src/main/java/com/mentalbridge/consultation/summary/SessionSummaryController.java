package com.mentalbridge.consultation.summary;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class SessionSummaryController {

	private final SessionSummaryService summaries;

	public SessionSummaryController(SessionSummaryService summaries) {
		this.summaries = summaries;
	}

	@GetMapping("/api/v1/specialist/appointments/{appointmentId}/session-summaries")
	SessionSummaryResponse.ListResponse specialistHistory(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID appointmentId) {
		return summaries.specialistHistory(RequestIdentity.subject(jwt), appointmentId);
	}

	@PostMapping("/api/v1/specialist/appointments/{appointmentId}/session-summaries")
	ResponseEntity<SessionSummaryResponse> publish(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID appointmentId,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128)
			@Pattern(regexp = "^[!-~]+$") String idempotencyKey,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody PublishSessionSummaryRequest request) {
		var response = summaries.publish(RequestIdentity.subject(jwt), appointmentId, idempotencyKey,
				version(ifMatch, false), request);
		return ResponseEntity.created(URI.create("/api/v1/appointments/" + appointmentId
				+ "/session-summaries/" + response.id())).eTag(Long.toString(response.version())).body(response);
	}

	@GetMapping("/api/v1/appointments/{appointmentId}/session-summaries")
	SessionSummaryResponse.ListResponse userHistory(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID appointmentId) {
		return summaries.userHistory(RequestIdentity.subject(jwt), appointmentId);
	}

	@PutMapping("/api/v1/session-summaries/{summaryId}/reuse-consent")
	ResponseEntity<SessionSummaryResponse> consent(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID summaryId, @RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody ReuseConsentRequest request) {
		var response = summaries.updateConsent(RequestIdentity.subject(jwt), summaryId,
				version(ifMatch, true), request.approved());
		return ResponseEntity.ok().eTag(Long.toString(response.reuseConsent().version())).body(response);
	}

	@PutMapping("/api/v1/agreed-next-steps/{nextStepId}")
	ResponseEntity<SessionSummaryResponse> updateNextStep(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID nextStepId, @RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody UpdateNextStepRequest request) {
		var response = summaries.updateNextStep(RequestIdentity.subject(jwt), nextStepId,
				version(ifMatch, true), request.state(), request.hidden());
		var updated = response.agreedNextSteps().stream().filter(step -> step.id().equals(nextStepId)).findFirst()
				.orElseThrow();
		return ResponseEntity.ok().eTag(Long.toString(updated.stateVersion())).body(response);
	}

	@GetMapping("/internal/v1/appointments/{appointmentId}/reusable-session-summaries/{summaryId}")
	SessionSummaryResponse reusable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@PathVariable UUID summaryId, @RequestParam long version) {
		var role = RequestIdentity.role(jwt);
		return summaries.reusable(RequestIdentity.subject(jwt), "USER".equals(role),
				"SPECIALIST".equals(role), appointmentId, summaryId, version);
	}

	private Long version(String ifMatch, boolean required) {
		if (ifMatch == null && !required) return null;
		if (ifMatch == null || !ifMatch.matches("\\\"[0-9]+\\\"")) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "SESSION_SUMMARY_VERSION_REQUIRED",
					"If-Match must contain the quoted current version");
		}
		try { return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)); }
		catch (NumberFormatException exception) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "SESSION_SUMMARY_VERSION_REQUIRED",
					"If-Match must contain the quoted current version");
		}
	}

	public record ReuseConsentRequest(boolean approved) { }

	public record UpdateNextStepRequest(AgreedNextStepState state, boolean hidden) {
		public UpdateNextStepRequest {
			if (state == null) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
					"state is required");
		}
	}
}
