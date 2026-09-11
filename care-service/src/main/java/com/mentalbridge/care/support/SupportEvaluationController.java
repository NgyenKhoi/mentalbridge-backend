package com.mentalbridge.care.support;

import java.net.URI;
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
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/support-evaluations")
@Validated
public class SupportEvaluationController {

	private final SupportEvaluationService evaluations;

	public SupportEvaluationController(SupportEvaluationService evaluations) {
		this.evaluations = evaluations;
	}

	@PostMapping
	ResponseEntity<SupportEvaluationService.EvaluationView> evaluate(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody SupportEvaluationRequest request) {
		var result = evaluations.evaluate(subject(jwt), key, correlationId == null ? UUID.randomUUID() : correlationId,
				new SupportEvaluationService.EvaluationCommand(request.phq9AssessmentId(), request.gad7AssessmentId()));
		return ResponseEntity.created(URI.create("/api/v1/support-evaluations/" + result.supportEvaluationId())).body(result);
	}

	@GetMapping("/{supportEvaluationId}")
	SupportEvaluationService.EvaluationView get(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportEvaluationId) {
		return evaluations.get(subject(jwt), supportEvaluationId);
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

	public record SupportEvaluationRequest(@NotNull UUID phq9AssessmentId, @NotNull UUID gad7AssessmentId) { }
}
