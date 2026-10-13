package com.mentalbridge.care.supportplan;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
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
@Validated
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
		var value = occurrences.change(userId, occurrenceId, Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)), request.state(), correlationId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(value.version()) + '"').body(value);
	}
	public record StateRequest(@NotNull @Pattern(regexp = "^(SCHEDULED|COMPLETED|SKIPPED)$") String state) { }
}
