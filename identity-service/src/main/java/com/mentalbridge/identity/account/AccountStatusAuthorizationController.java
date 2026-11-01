package com.mentalbridge.identity.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AccountStatusAuthorizationController {

	private final AccountStatusAuthorizationService authorization;
	private final AccountStatusAuthorizationProperties properties;

	AccountStatusAuthorizationController(AccountStatusAuthorizationService authorization,
			AccountStatusAuthorizationProperties properties) {
		this.authorization = authorization;
		this.properties = properties;
	}

	@GetMapping("/internal/v1/accounts/{accountId}/authorization")
	ResponseEntity<AuthorizationResult> authorize(@PathVariable UUID accountId,
			@RequestHeader(value = "X-MentalBridge-Service-Id", required = false) String serviceId,
			@RequestHeader(value = "X-MentalBridge-Service-Token", required = false) String candidate) {
		var expected = properties.tokenFor(serviceId == null ? "" : serviceId);
		if (expected.length() < 32 || candidate == null || !MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8))) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(new AuthorizationResult(authorization.isActive(accountId)));
	}

	record AuthorizationResult(boolean active) {
		@Override
		public String toString() {
			return "AccountStatusAuthorizationResult[REDACTED]";
		}
	}
}
