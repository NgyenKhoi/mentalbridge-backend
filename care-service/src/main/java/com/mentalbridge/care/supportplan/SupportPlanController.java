package com.mentalbridge.care.supportplan;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanService.ProposeCommand;
import com.mentalbridge.care.supportplan.SupportPlanService.SupportPlanView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/support-plans")
@Validated
public class SupportPlanController {

	private final SupportPlanService plans;

	public SupportPlanController(SupportPlanService plans) {
		this.plans = plans;
	}

	@PostMapping
	ResponseEntity<SupportPlanView> propose(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody ProposeRequest request) {
		var plan = plans.propose(subject(jwt), jwt.getTokenValue(), key,
				correlationId == null ? UUID.randomUUID() : correlationId,
				new ProposeCommand(request.sourceSupportEvaluationId()));
		return ResponseEntity.created(URI.create("/api/v1/support-plans/current-draft"))
				.header(HttpHeaders.ETAG, '"' + Long.toString(plan.version()) + '"').body(plan);
	}

	@GetMapping("/current-draft")
	ResponseEntity<SupportPlanView> current(@AuthenticationPrincipal Jwt jwt) {
		var plan = plans.current(subject(jwt));
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

	public record ProposeRequest(@NotNull UUID sourceSupportEvaluationId) { }
}
