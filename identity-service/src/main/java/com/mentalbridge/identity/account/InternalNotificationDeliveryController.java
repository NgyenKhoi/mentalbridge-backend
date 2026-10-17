package com.mentalbridge.identity.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/notification-delivery-contacts")
class InternalNotificationDeliveryController {

	private final AccountQueryService accounts;
	private final byte[] serviceToken;

	InternalNotificationDeliveryController(AccountQueryService accounts,
			@Value("${mentalbridge.identity.notification-service-token:}") String serviceToken) {
		this.accounts = accounts;
		this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
	}

	@GetMapping("/{accountId}")
	DeliveryContact deliveryContact(@PathVariable UUID accountId,
			@RequestHeader(value = "X-MentalBridge-Service-Token", required = false) String candidate) {
		if (serviceToken.length == 0 || candidate == null || !MessageDigest.isEqual(serviceToken,
				candidate.getBytes(StandardCharsets.UTF_8))) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		var account = accounts.findById(accountId)
				.filter(value -> value.status() == AccountStatus.ACTIVE && value.emailVerifiedAt() != null)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return new DeliveryContact(account.email());
	}

	record DeliveryContact(String email) {
	}
}
