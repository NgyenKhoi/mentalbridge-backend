package com.mentalbridge.care.supportplan;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.Confirmation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v2/support-plans/{supportPlanId}/adaptive-review")
public class AdaptiveSupportPlanReviewController {
	private final AdaptiveSupportPlanReviewService reviews;

	public AdaptiveSupportPlanReviewController(AdaptiveSupportPlanReviewService reviews) { this.reviews = reviews; }

	@GetMapping
	ResponseEntity<AdaptiveSupportPlanReviewView> review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID supportPlanId,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestParam(required = false) UUID reassessmentSummaryId,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlation) {
		var view = reviews.review(subject(jwt), supportPlanId, ifMatch == null ? null : version(ifMatch),
				reassessmentSummaryId, jwt.getTokenValue(), correlation(correlation));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(Long.toString(view.planVersion())).body(view);
	}

	@PostMapping("/confirm")
	ResponseEntity<Confirmation> confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID supportPlanId,
			@RequestHeader("If-Match") String ifMatch,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlation,
			@Valid @RequestBody ConfirmRequest request) {
		var result = reviews.confirm(subject(jwt), supportPlanId, version(ifMatch), request.command(),
				jwt.getTokenValue(), key, correlation(correlation));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(Long.toString(result.plan().version())).body(result);
	}

	private long version(String value) {
		if (value == null || !value.matches("^\"[0-9]+\"$")) throw invalidVersion();
		try { return Long.parseLong(value.substring(1, value.length() - 1)); }
		catch (NumberFormatException exception) { throw invalidVersion(); }
	}
	private ApiException invalidVersion() { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "If-Match must contain a valid plan version"); }
	private UUID subject(Jwt jwt) {
		try { return UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authenticated account identifier is invalid"); }
	}
	private UUID correlation(UUID value) { return value == null ? UUID.randomUUID() : value; }

	public record ConfirmRequest(@NotBlank @Size(min = 16, max = 128) String reviewFingerprint,
			@NotBlank @Size(max = 128) String optionId, UUID reassessmentSummaryId, UUID proposedPlanId,
			@Min(0) Long proposedVersion) {
		AdaptiveSupportPlanReviewService.ConfirmCommand command() {
			return new AdaptiveSupportPlanReviewService.ConfirmCommand(reviewFingerprint, optionId,
					reassessmentSummaryId, proposedPlanId, proposedVersion);
		}
	}
}
