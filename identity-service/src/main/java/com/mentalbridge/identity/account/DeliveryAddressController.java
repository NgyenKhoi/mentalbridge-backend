package com.mentalbridge.identity.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/accounts")
public class DeliveryAddressController {

	private final AccountRepository accounts;
	private final DeliveryAddressProperties properties;

	public DeliveryAddressController(AccountRepository accounts, DeliveryAddressProperties properties) {
		this.accounts = accounts;
		this.properties = properties;
	}

	@GetMapping("/{accountId}/verified-email")
	public DeliveryAddress get(@PathVariable UUID accountId,
			@RequestHeader(name = "X-MentalBridge-Service-Token", required = false) String serviceToken) {
		requireToken(serviceToken);
		return accounts.findById(accountId)
				.filter(account -> account.role() == RoleCode.USER && account.status() == AccountStatus.ACTIVE
						&& account.emailVerifiedAt() != null)
				.map(account -> new DeliveryAddress(account.id(), account.email()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private void requireToken(String supplied) {
		var expected = properties.serviceToken();
		if (supplied == null || expected == null || expected.isBlank()
				|| !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
	}

	public record DeliveryAddress(UUID accountId, String email) {
	}
}
