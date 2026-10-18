package com.mentalbridge.care.supportplan;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v2/support-plans")
public class NativeSupportPlanController {
	private final NativeSupportPlanService plans;

	public NativeSupportPlanController(NativeSupportPlanService plans) {
		this.plans = plans;
	}

	@PostMapping
	ResponseEntity<SupportPlanV3Service.SupportPlanV3View> propose(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlation,
			@Valid @RequestBody ProposalRequest request) {
		return response(HttpStatus.CREATED, plans.propose(subject(jwt), request.sourceSupportEvaluationId(),
				jwt.getTokenValue(), key, correlation(correlation)));
	}

	@GetMapping("/history")
	NativeSupportPlanService.History history(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit,
			@RequestParam(required = false) @Size(max = 256) String cursor) {
		return plans.history(subject(jwt), limit, cursor);
	}

	@PostMapping("/{supportPlanId}/activate")
	ResponseEntity<SupportPlanV3Service.SupportPlanV3View> activate(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportPlanId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlation) {
		return response(HttpStatus.OK, plans.activate(subject(jwt), supportPlanId, version(ifMatch),
				jwt.getTokenValue(), key, correlation(correlation)));
	}

	@PostMapping("/{supportPlanId}/cycle-continuation")
	ResponseEntity<SupportPlanV3Service.SupportPlanV3View> continueCycle(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportPlanId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlation) {
		return response(HttpStatus.OK, plans.continueCycle(subject(jwt), supportPlanId, version(ifMatch),
				jwt.getTokenValue(), key, correlation(correlation)));
	}

	@PostMapping("/{supportPlanId}/replacement-review")
	NativeSupportPlanService.ReplacementReview review(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportPlanId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlation,
			@Valid @RequestBody ReplacementRequest request) {
		return plans.reviewReplacement(subject(jwt), supportPlanId, version(ifMatch), jwt.getTokenValue(),
				request.command(), correlation(correlation));
	}

	@PostMapping("/{supportPlanId}/replace")
	ResponseEntity<SupportPlanV3Service.SupportPlanV3View> replace(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportPlanId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlation,
			@Valid @RequestBody ReplacementRequest request) {
		return response(HttpStatus.OK, plans.replace(subject(jwt), supportPlanId, version(ifMatch),
				jwt.getTokenValue(), key, request.command(), correlation(correlation)));
	}

	@PutMapping("/{supportPlanId}/status")
	ResponseEntity<SupportPlanV3Service.SupportPlanV3View> status(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportPlanId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@Valid @RequestBody SupportPlanController.ChangeStatusRequest request) {
		return transition(jwt, supportPlanId, ifMatch, request.status(), request.completionReason());
	}

	private ResponseEntity<SupportPlanV3Service.SupportPlanV3View> transition(Jwt jwt, UUID id, String match,
			String state, String reason) {
		return response(HttpStatus.OK, plans.transition(subject(jwt), id, version(match), state, reason));
	}

	private ResponseEntity<SupportPlanV3Service.SupportPlanV3View> response(HttpStatus status,
			SupportPlanV3Service.SupportPlanV3View view) {
		return ResponseEntity.status(status).header(HttpHeaders.ETAG, '"' + Long.toString(view.version()) + '"').body(view);
	}
	private UUID correlation(UUID value) { return value == null ? UUID.randomUUID() : value; }
	private long version(String match) {
		if (match == null || !match.matches("^\"[0-9]+\"$")) throw invalidVersion();
		try { return Long.parseLong(match.substring(1, match.length() - 1)); }
		catch (NumberFormatException exception) { throw invalidVersion(); }
	}
	private ApiException invalidVersion() {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "If-Match must contain a valid plan version");
	}
	private UUID subject(Jwt jwt) {
		try { return UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authenticated account identifier is invalid"); }
	}
	public record ProposalRequest(@NotNull UUID sourceSupportEvaluationId) { }
	public record ReplacementRequest(@NotNull UUID currentSupportPlanId, @NotNull @Min(0) Long currentVersion,
			@NotNull UUID reassessmentSummaryId) {
		SupportPlanService.ReplacePlanCommand command() {
			return new SupportPlanService.ReplacePlanCommand(currentSupportPlanId, currentVersion, reassessmentSummaryId);
		}
	}
}
