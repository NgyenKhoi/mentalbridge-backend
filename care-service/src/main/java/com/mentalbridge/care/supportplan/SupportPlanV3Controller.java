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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

@RestController
@RequestMapping("/api/v2")
@Validated
public class SupportPlanV3Controller {

	private final SupportPlanV3Service plans;

	public SupportPlanV3Controller(SupportPlanV3Service plans) {
		this.plans = plans;
	}

	@GetMapping("/support-plans/current")
	ResponseEntity<SupportPlanV3Service.SupportPlanV3View> current(@AuthenticationPrincipal Jwt jwt) {
		var plan = plans.current(subject(jwt));
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

	private UUID subject(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}
}
