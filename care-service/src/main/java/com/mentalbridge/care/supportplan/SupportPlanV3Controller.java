package com.mentalbridge.care.supportplan;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanScheduleService.ScheduleCommand;
import com.mentalbridge.care.supportplan.SupportPlanScheduleService.ScheduleInput;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v2")
@Validated
public class SupportPlanV3Controller {

	private final SupportPlanV3Service plans;
	private final SupportPlanScheduleService schedules;

	public SupportPlanV3Controller(SupportPlanV3Service plans, SupportPlanScheduleService schedules) {
		this.plans = plans;
		this.schedules = schedules;
	}

	@GetMapping("/support-plans/current")
	ResponseEntity<SupportPlanV3Service.SupportPlanV3View> current(@AuthenticationPrincipal Jwt jwt) {
		var plan = plans.current(subject(jwt));
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(plan.version()) + '"').body(plan);
	}

	@GetMapping("/support-plans/current-draft")
	ResponseEntity<SupportPlanV3Service.SupportPlanV3View> currentDraft(@AuthenticationPrincipal Jwt jwt) {
		var plan = plans.currentDraft(subject(jwt));
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(plan.version()) + '"').body(plan);
	}

	@GetMapping("/support-plans/{supportPlanId}")
	ResponseEntity<SupportPlanV3Service.SupportPlanV3View> get(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportPlanId) {
		var plan = plans.get(subject(jwt), supportPlanId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(plan.version()) + '"').body(plan);
	}

	@GetMapping("/support-plan-occurrences")
	SupportPlanV3Service.ActionOccurrenceListView occurrences(@AuthenticationPrincipal Jwt jwt,
			@RequestParam LocalDate from, @RequestParam LocalDate through) {
		return plans.occurrences(subject(jwt), from, through);
	}

	@PutMapping("/support-plans/{supportPlanId}/actions/{actionId}/schedule")
	ResponseEntity<SupportPlanV3Service.SupportPlanV3View> schedule(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportPlanId, @PathVariable UUID actionId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128)
					@Pattern(regexp = "^[!-~]+$") String idempotencyKey,
			@Valid @RequestBody ScheduleUpdateRequest request) {
		var input = request.schedule() == null ? null : new ScheduleInput(
				request.schedule().recurrenceType(), request.schedule().daysOfWeek(),
				request.schedule().preferredDaypart(), request.schedule().localTime(),
				request.schedule().estimatedDurationMinutes(), request.schedule().timezone(),
				request.schedule().effectiveFrom(), request.schedule().effectiveUntil(),
				request.schedule().reminderEnabled());
		schedules.update(subject(jwt), supportPlanId, actionId, version(ifMatch),
				idempotencyKey, new ScheduleCommand(input));
		var plan = plans.get(subject(jwt), supportPlanId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(plan.version()) + '"').body(plan);
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

	public record ScheduleUpdateRequest(@Valid ScheduleRequest schedule) { }
	public record ScheduleRequest(
			@NotNull @Pattern(regexp = "^(ONCE|DAILY|WEEKLY)$") String recurrenceType,
			@NotNull @Size(max = 7) List<@Min(1) @Max(7) Integer> daysOfWeek,
			@Pattern(regexp = "^(MORNING|AFTERNOON|EVENING|FLEXIBLE)$") String preferredDaypart,
			@NotNull LocalTime localTime,
			@NotNull @Min(5) @Max(180) Integer estimatedDurationMinutes,
			@NotBlank @Size(max = 64) String timezone,
			@NotNull LocalDate effectiveFrom,
			LocalDate effectiveUntil,
			boolean reminderEnabled) { }
}
