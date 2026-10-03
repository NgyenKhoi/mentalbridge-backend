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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.PlanChangeRequestService.PlanChangeRequestView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@Validated
class PlanChangeRequestController {

	private final PlanChangeRequestService requests;

	PlanChangeRequestController(PlanChangeRequestService requests) {
		this.requests = requests;
	}

	@PostMapping("/api/v1/plan-change-requests")
	ResponseEntity<PlanChangeRequestView> create(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody CreateRequest request) {
		var created = requests.create(subject(jwt), jwt.getTokenValue(), request.proposalId(), key,
				correlationId == null ? UUID.randomUUID() : correlationId);
		return ResponseEntity.created(URI.create("/api/v1/plan-change-requests/" + created.requestId()))
				.header(HttpHeaders.ETAG, etag(created.version())).body(created);
	}

	@GetMapping("/api/v1/plan-change-requests/by-proposal/{proposalId}")
	ResponseEntity<PlanChangeRequestView> userProposal(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID proposalId) {
		return response(requests.userProposal(subject(jwt), proposalId));
	}

	@GetMapping("/api/v1/specialist/plan-change-requests/by-proposal/{proposalId}")
	ResponseEntity<PlanChangeRequestView> specialistProposal(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID proposalId) {
		return response(requests.specialistProposal(subject(jwt), proposalId));
	}

	@PutMapping("/api/v1/plan-change-requests/{requestId}/decision")
	ResponseEntity<PlanChangeRequestView> decide(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID requestId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody DecisionRequest request) {
		return response(requests.decide(subject(jwt), jwt.getTokenValue(), requestId, version(ifMatch), key,
				request.decision(), correlationId == null ? UUID.randomUUID() : correlationId));
	}

	private ResponseEntity<PlanChangeRequestView> response(PlanChangeRequestView view) {
		return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(view.version())).body(view);
	}

	private String etag(long version) {
		return '"' + Long.toString(version) + '"';
	}

	private long version(String ifMatch) {
		return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
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

	record CreateRequest(@NotNull UUID proposalId) { }
	record DecisionRequest(@NotNull @Pattern(regexp = "^(ACCEPT|REJECT)$") String decision) { }
}
