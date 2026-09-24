package com.mentalbridge.care.reassessment;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.reassessment.ReassessmentSelfReportService.CreateCommand;
import com.mentalbridge.care.reassessment.ReassessmentSelfReportService.ReplaceCommand;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/reassessment-self-reports")
@Validated
public class ReassessmentSelfReportController {

	private final ReassessmentSelfReportService reports;

	public ReassessmentSelfReportController(ReassessmentSelfReportService reports) {
		this.reports = reports;
	}

	@PostMapping
	ResponseEntity<ReassessmentSelfReportView> create(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@Valid @RequestBody CreateRequest request) {
		var value = reports.create(subject(jwt), key, new CreateCommand(request.currentPeriod().toPeriod(),
				request.currentExperience(), request.helpfulContext(), request.difficultContext()));
		return ResponseEntity.created(URI.create("/api/v1/reassessment-self-reports/" + value.selfReportId()))
				.header(HttpHeaders.ETAG, etag(value.version())).body(value);
	}

	@GetMapping("/current")
	ResponseEntity<ReassessmentSelfReportView> current(@AuthenticationPrincipal Jwt jwt) {
		var value = reports.current(subject(jwt));
		return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(value.version())).body(value);
	}

	@PutMapping("/{selfReportId}")
	ResponseEntity<ReassessmentSelfReportView> replace(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID selfReportId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@Valid @RequestBody ReplaceRequest request) {
		var value = reports.replace(subject(jwt), selfReportId, version(ifMatch),
				new ReplaceCommand(request.currentExperience(), request.helpfulContext(), request.difficultContext()));
		return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(value.version())).body(value);
	}

	@DeleteMapping("/{selfReportId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID selfReportId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch) {
		reports.delete(subject(jwt), selfReportId, version(ifMatch));
		return ResponseEntity.noContent().build();
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

	private String etag(long version) {
		return '"' + Long.toString(version) + '"';
	}

	public record CreateRequest(@Valid @NotNull PeriodRequest currentPeriod,
			@NotNull @Pattern(regexp = "^(BETTER|ABOUT_THE_SAME|MORE_DIFFICULT|UNSURE)$") String currentExperience,
			@Size(max = 500) String helpfulContext, @Size(max = 500) String difficultContext) { }

	public record ReplaceRequest(
			@NotNull @Pattern(regexp = "^(BETTER|ABOUT_THE_SAME|MORE_DIFFICULT|UNSURE)$") String currentExperience,
			@Size(max = 500) String helpfulContext, @Size(max = 500) String difficultContext) { }

	public record PeriodRequest(@NotNull Instant startAt, @NotNull Instant endAt) {
		Period toPeriod() {
			return new Period(startAt, endAt);
		}
	}
}
