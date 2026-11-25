package com.mentalbridge.care.reassessment;

import java.net.URI;
import java.time.Instant;
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

import com.mentalbridge.care.reassessment.ReassessmentSummaryService.ComposeCommand;
import com.mentalbridge.care.reassessment.ReassessmentSummaryService.HistoryView;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/reassessment-summaries")
@Validated
public class ReassessmentSummaryController {

	private final ReassessmentSummaryService summaries;

	public ReassessmentSummaryController(ReassessmentSummaryService summaries) {
		this.summaries = summaries;
	}

	@PostMapping
	ResponseEntity<ReassessmentSummaryView> compose(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody ComposeRequest request) {
		var result = summaries.compose(subject(jwt), jwt.getTokenValue(), key,
				correlationId == null ? UUID.randomUUID() : correlationId,
				new ComposeCommand(request.phq9AssessmentId(), request.gad7AssessmentId(),
						request.journalAnalysisId(), request.journalJobId(), request.previousPeriod().toPeriod(),
						request.currentPeriod().toPeriod(), request.selfReportId()));
		return ResponseEntity.created(URI.create("/api/v1/reassessment-summaries/" + result.summaryId())).body(result);
	}

	@GetMapping
	HistoryView history(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
			@RequestParam(required = false) @Size(max = 256) String cursor) {
		return summaries.history(subject(jwt), limit, cursor);
	}

	@GetMapping("/current")
	ReassessmentSummaryView current(@AuthenticationPrincipal Jwt jwt) {
		return summaries.current(subject(jwt));
	}

	@GetMapping("/context")
	ReassessmentSummaryService.ReassessmentContextView context(@AuthenticationPrincipal Jwt jwt) {
		return summaries.context(subject(jwt));
	}

	@GetMapping("/{summaryId}")
	ReassessmentSummaryView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID summaryId) {
		return summaries.get(subject(jwt), summaryId);
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

	public record ComposeRequest(@NotNull UUID phq9AssessmentId, @NotNull UUID gad7AssessmentId,
			UUID journalAnalysisId, UUID journalJobId, @Valid @NotNull PeriodRequest previousPeriod,
			@Valid @NotNull PeriodRequest currentPeriod, UUID selfReportId) { }

	public record PeriodRequest(@NotNull Instant startAt, @NotNull Instant endAt) {
		Period toPeriod() {
			return new Period(startAt, endAt);
		}
	}
}
