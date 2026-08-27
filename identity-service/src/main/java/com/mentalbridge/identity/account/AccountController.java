package com.mentalbridge.identity.account;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

	private final AccountQueryService accounts;

	public AccountController(AccountQueryService accounts) {
		this.accounts = accounts;
	}

	@GetMapping
	AccountResponse getOwnAccount(@AuthenticationPrincipal Jwt jwt) {
		var account = accounts.findById(UUID.fromString(jwt.getSubject()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return new AccountResponse(account.accountId(), account.email(), account.status(), List.of(account.role()),
				account.emailVerifiedAt() != null, account.createdAt(), account.updatedAt(), account.version());
	}

	public record AccountResponse(UUID accountId, String email, AccountStatus status, List<RoleCode> roles,
			boolean emailVerified, Instant createdAt, Instant updatedAt, long version) {
	}

}
