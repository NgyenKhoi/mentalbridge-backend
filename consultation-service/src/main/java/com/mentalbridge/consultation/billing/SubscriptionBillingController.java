package com.mentalbridge.consultation.billing;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

import jakarta.validation.Valid;

@RestController
public class SubscriptionBillingController {

	private final SubscriptionBillingService billing;

	public SubscriptionBillingController(SubscriptionBillingService billing) {
		this.billing = billing;
	}

	@GetMapping("/api/v1/service-plans")
	PlanCatalogueResponse catalogue() {
		return billing.catalogue();
	}

	@PostMapping("/api/v1/subscriptions/checkout")
	ResponseEntity<PaymentResponse> checkout(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody CheckoutRequest request) {
		var result = billing.checkout(RequestIdentity.subject(jwt), request.planVersionId(), idempotencyKey);
		return ResponseEntity.created(URI.create("/api/v1/payments/" + result.paymentId())).body(result);
	}

	@GetMapping("/api/v1/payments/{paymentId}")
	PaymentResponse payment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
		return billing.payment(RequestIdentity.subject(jwt), paymentId);
	}

	@GetMapping("/api/v1/payments")
	PaymentResponse.History history(@AuthenticationPrincipal Jwt jwt) {
		return billing.history(RequestIdentity.subject(jwt));
	}
}
