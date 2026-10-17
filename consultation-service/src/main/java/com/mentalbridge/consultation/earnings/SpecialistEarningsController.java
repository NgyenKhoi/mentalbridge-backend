package com.mentalbridge.consultation.earnings;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
public class SpecialistEarningsController {

	private final SpecialistPayoutService payouts;

	public SpecialistEarningsController(SpecialistPayoutService payouts) {
		this.payouts = payouts;
	}

	@GetMapping("/api/v1/specialist/earnings")
	SpecialistEarningsResponse earnings(@AuthenticationPrincipal Jwt jwt) {
		return payouts.earnings(RequestIdentity.subject(jwt));
	}

	@PutMapping("/api/v1/specialist/payout-destination")
	SpecialistEarningsResponse.Destination destination(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody SavePayoutDestinationRequest request) {
		return payouts.saveDestination(RequestIdentity.subject(jwt), request);
	}

	@PostMapping("/api/v1/specialist/payouts")
	SpecialistEarningsResponse request(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128) String idempotencyKey,
			@Valid @RequestBody CreatePayoutRequest request) {
		return payouts.requestPayout(RequestIdentity.subject(jwt), request.destinationId(), idempotencyKey);
	}
}
