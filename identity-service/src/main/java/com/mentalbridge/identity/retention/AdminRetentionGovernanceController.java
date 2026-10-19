package com.mentalbridge.identity.retention;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/admin/retention")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminRetentionGovernanceController {

	private final AdminRetentionGovernanceService service;

	public AdminRetentionGovernanceController(AdminRetentionGovernanceService service) {
		this.service = service;
	}

	@GetMapping("/policies")
	public ResponseEntity<List<AdminRetentionGovernanceService.RetentionPolicyResponse>> getPolicies(
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			HttpServletRequest request) {
		UUID effectiveCorrelation = correlation(request, correlationId);
		return ResponseEntity.ok()
				.header("X-Correlation-Id", effectiveCorrelation.toString())
				.body(service.getRetentionPolicies());
	}

	@PostMapping("/policies/{policyId}/sweep")
	public ResponseEntity<AdminRetentionGovernanceService.RetentionExecutionResponse> triggerSweep(
			@PathVariable String policyId,
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			HttpServletRequest request) {
		UUID effectiveCorrelation = correlation(request, correlationId);
		UUID actorId = actor(jwt);
		var result = service.triggerRetentionSweep(policyId, actorId, effectiveCorrelation);
		return ResponseEntity.ok()
				.header("X-Correlation-Id", effectiveCorrelation.toString())
				.body(result);
	}

	@GetMapping("/deletions")
	public ResponseEntity<AdminRetentionGovernanceService.DeletionRequestPage> getDeletions(
			@RequestParam(required = false) DeletionRequestStatus status,
			@RequestParam(required = false) DeletionHoldType holdType,
			@RequestParam(required = false) @Min(1) @Max(100) Integer limit,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			HttpServletRequest request) {
		UUID effectiveCorrelation = correlation(request, correlationId);
		return ResponseEntity.ok()
				.header("X-Correlation-Id", effectiveCorrelation.toString())
				.body(service.getDeletionRequests(status, holdType, limit));
	}

	@PostMapping("/deletions/{requestId}/retry")
	public ResponseEntity<AdminRetentionGovernanceService.DeletionRequestResponse> retryDeletion(
			@PathVariable UUID requestId,
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			HttpServletRequest request) {
		UUID effectiveCorrelation = correlation(request, correlationId);
		UUID actorId = actor(jwt);
		var result = service.retryDeletion(requestId, actorId, effectiveCorrelation);
		return ResponseEntity.ok()
				.header("X-Correlation-Id", effectiveCorrelation.toString())
				.body(result);
	}

	@PostMapping("/deletions/{requestId}/holds")
	public ResponseEntity<AdminRetentionGovernanceService.DeletionRequestResponse> applyHold(
			@PathVariable UUID requestId,
			@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody AdminRetentionGovernanceService.ApplyHoldRequest body,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			HttpServletRequest request) {
		UUID effectiveCorrelation = correlation(request, correlationId);
		UUID actorId = actor(jwt);
		var result = service.applyHold(requestId, body.holdType(), body.holdReason(), actorId, effectiveCorrelation);
		return ResponseEntity.ok()
				.header("X-Correlation-Id", effectiveCorrelation.toString())
				.body(result);
	}

	@DeleteMapping("/deletions/{requestId}/holds")
	public ResponseEntity<AdminRetentionGovernanceService.DeletionRequestResponse> releaseHold(
			@PathVariable UUID requestId,
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			HttpServletRequest request) {
		UUID effectiveCorrelation = correlation(request, correlationId);
		UUID actorId = actor(jwt);
		var result = service.releaseHold(requestId, actorId, effectiveCorrelation);
		return ResponseEntity.ok()
				.header("X-Correlation-Id", effectiveCorrelation.toString())
				.body(result);
	}

	private UUID actor(Jwt jwt) {
		return jwt != null && jwt.getSubject() != null ? UUID.fromString(jwt.getSubject()) : null;
	}

	private UUID correlation(HttpServletRequest request, UUID supplied) {
		UUID effective = supplied == null ? UUID.randomUUID() : supplied;
		if (request != null) {
			request.setAttribute("correlationId", effective);
		}
		return effective;
	}
}

