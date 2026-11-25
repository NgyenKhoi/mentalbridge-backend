package com.mentalbridge.care.supportplan;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/v2/support-plan-occurrences")
public class NativeActionOccurrenceController {
	private final NativeActionOccurrenceService occurrences;
	public NativeActionOccurrenceController(NativeActionOccurrenceService occurrences) { this.occurrences = occurrences; }

	@PutMapping("/{occurrenceId}/state")
	ResponseEntity<SupportPlanV3Service.ActionOccurrenceView> change(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID occurrenceId, @RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId, @Valid @RequestBody StateRequest request) {
		UUID userId;
		try { userId = UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authenticated account identifier is invalid"); }
		var value = occurrences.change(userId, occurrenceId, version(ifMatch), request.state(), correlationId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(value.version()) + '"').body(value);
	}

	@PutMapping("/{occurrenceId}/engagement")
	ResponseEntity<SupportPlanV3Service.ActionOccurrenceView> engagement(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID occurrenceId, @RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody SupportPlanActivityOccurrenceController.ReplaceEngagementRequest request) {
		var value = occurrences.replaceEngagement(subject(jwt), occurrenceId, version(ifMatch), request.state(),
				request.hidden(), request.helpfulness(), request.barrierCode(), request.reflection(),
				request.summaryReuseApproved(), correlationId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(value.version()) + '"').body(value);
	}

	@DeleteMapping("/{occurrenceId}/engagement")
	ResponseEntity<SupportPlanV3Service.ActionOccurrenceView> deleteEngagement(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID occurrenceId, @RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
		var value = occurrences.deleteEngagement(subject(jwt), occurrenceId, version(ifMatch), correlationId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(value.version()) + '"').body(value);
	}

	private UUID subject(Jwt jwt) {
		try { return UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authenticated account identifier is invalid");
		}
	}

	private long version(String ifMatch) {
		if (ifMatch == null || !ifMatch.matches("^\"[0-9]+\"$")) throw invalidVersion();
		try { return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)); }
		catch (NumberFormatException exception) { throw invalidVersion(); }
	}

	private ApiException invalidVersion() {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "If-Match must contain a valid occurrence version");
	}

	public record StateRequest(@NotNull @Pattern(regexp = "^(SCHEDULED|COMPLETED|SKIPPED)$") String state) { }
}
