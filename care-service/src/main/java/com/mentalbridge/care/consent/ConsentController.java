package com.mentalbridge.care.consent;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

@RestController
@RequestMapping("/api/v1")
public class ConsentController {

	private final ConsentService consents;

	public ConsentController(ConsentService consents) {
		this.consents = consents;
	}

	@GetMapping("/consents")
	ConsentCollection current(@AuthenticationPrincipal Jwt jwt) {
		return new ConsentCollection(consents.current(subject(jwt)).stream().map(ConsentDecisionResponse::from).toList());
	}

	@PostMapping("/consent-decisions")
	ResponseEntity<ConsentDecisionResponse> record(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String idempotencyKey,
			@Valid @RequestBody ConsentDecisionRequest request) {
		var decision = consents.record(subject(jwt), idempotencyKey, request.command());
		return ResponseEntity.created(URI.create("/api/v1/consent-decisions/" + decision.decisionId()))
				.body(ConsentDecisionResponse.from(decision));
	}

	private UUID subject(Jwt jwt) {
		try { return UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) {
			throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}

	public record ConsentDecisionRequest(@NotBlank String consentType, @NotBlank @Size(max = 64) String policyVersion,
			boolean granted) {
		ConsentService.DecisionCommand command() {
			return new ConsentService.DecisionCommand(consentType, policyVersion, granted);
		}
	}

	public record ConsentDecisionResponse(UUID decisionId, String consentType, String policyVersion, boolean granted,
			java.time.Instant decidedAt) {
		static ConsentDecisionResponse from(ConsentService.DecisionView value) {
			return new ConsentDecisionResponse(value.decisionId(), value.consentType(), value.policyVersion(),
					value.granted(), value.decidedAt());
		}
	}

	public record ConsentCollection(List<ConsentDecisionResponse> decisions) {
	}
}
