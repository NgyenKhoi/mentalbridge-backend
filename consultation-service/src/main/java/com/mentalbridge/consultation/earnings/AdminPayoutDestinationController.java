package com.mentalbridge.consultation.earnings;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
public class AdminPayoutDestinationController {

	private final PayoutDestinationService destinations;

	public AdminPayoutDestinationController(PayoutDestinationService destinations) {
		this.destinations = destinations;
	}

	@GetMapping("/api/v1/admin/payout-destinations")
	ResponseEntity<PayoutDestinationService.AdminList> list(
			@RequestParam(defaultValue = "PENDING_CONFIRMATION") String status,
			@RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(destinations.list(status, limit, offset));
	}

	@GetMapping("/api/v1/admin/payout-destinations/{destinationId}")
	ResponseEntity<PayoutDestinationService.AdminDetail> detail(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID destinationId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(destinations.detail(RequestIdentity.subject(jwt), destinationId));
	}

	@PostMapping("/api/v1/admin/payout-destinations/{destinationId}/confirm")
	ResponseEntity<SpecialistEarningsResponse.Destination> confirm(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID destinationId,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128) String key,
			@Valid @RequestBody ConfirmPayoutDestinationRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(destinations.confirm(RequestIdentity.subject(jwt), destinationId, key, request));
	}
}
