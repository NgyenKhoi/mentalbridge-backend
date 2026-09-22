package com.mentalbridge.care.supportplan;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/support-plan-occurrences")
@Validated
public class SupportPlanActivityOccurrenceController {

	private final SupportPlanActivityOccurrenceService occurrences;

	public SupportPlanActivityOccurrenceController(SupportPlanActivityOccurrenceService occurrences) {
		this.occurrences = occurrences;
	}

	@GetMapping
	SupportPlanActivityOccurrenceService.OccurrenceListView list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam LocalDate from, @RequestParam LocalDate through) {
		return occurrences.list(subject(jwt), from, through);
	}

	@GetMapping("/{occurrenceId}")
	ResponseEntity<SupportPlanActivityOccurrenceService.OccurrenceView> detail(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID occurrenceId) {
		var value = occurrences.detail(subject(jwt), occurrenceId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(value.version()) + '"').body(value);
	}

	@PutMapping("/{occurrenceId}/state")
	ResponseEntity<SupportPlanActivityOccurrenceService.OccurrenceView> state(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID occurrenceId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@Valid @RequestBody ChangeStateRequest request) {
		var value = occurrences.changeState(subject(jwt), occurrenceId, version(ifMatch), request.state());
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(value.version()) + '"').body(value);
	}

	@PutMapping("/{occurrenceId}/engagement")
	ResponseEntity<SupportPlanActivityOccurrenceService.OccurrenceView> engagement(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID occurrenceId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
			@Valid @RequestBody ReplaceEngagementRequest request) {
		var value = occurrences.replaceEngagement(subject(jwt), occurrenceId, version(ifMatch), request.state(),
				request.hidden(), request.helpfulness(), request.barrierCode(), request.reflection(),
				request.summaryReuseApproved(), correlationId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(value.version()) + '"').body(value);
	}

	@DeleteMapping("/{occurrenceId}/engagement")
	ResponseEntity<SupportPlanActivityOccurrenceService.OccurrenceView> deleteEngagement(
			@AuthenticationPrincipal Jwt jwt, @PathVariable UUID occurrenceId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId) {
		var value = occurrences.deleteEngagement(subject(jwt), occurrenceId, version(ifMatch), correlationId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(value.version()) + '"').body(value);
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

	private long version(String ifMatch) {
		return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
	}

	public record ChangeStateRequest(@NotNull @Pattern(regexp = "^(COMPLETED|SKIPPED)$") String state) { }
	public record ReplaceEngagementRequest(
			@NotNull @Pattern(regexp = "^(SCHEDULED|COMPLETED|SKIPPED)$") String state,
			@NotNull Boolean hidden,
			@Pattern(regexp = "^(NOT_HELPFUL|A_LITTLE_HELPFUL|HELPFUL|VERY_HELPFUL)$") String helpfulness,
			@Pattern(regexp = "^(LOW_ENERGY|NOT_ENOUGH_TIME|DIFFICULT_TO_START|NOT_A_GOOD_FIT|OTHER)$") String barrierCode,
			@Size(max = 500) String reflection,
			@NotNull Boolean summaryReuseApproved) { }
}
