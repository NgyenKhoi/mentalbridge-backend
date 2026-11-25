package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AccountStatusAuthorizationTests {
	private static final String TOKEN = "synthetic-account-status-token-123456789";
	private static final String COMMUNITY_TOKEN = "synthetic-community-status-token-123456";
	private static final String CONSULTATION_TOKEN = "synthetic-consultation-status-token-123";

	@Test
	void usesTheLocalAccountRepositoryForCurrentActiveState() {
		var accounts = mock(AccountRepository.class);
		var accountId = UUID.randomUUID();
		when(accounts.existsByIdAndStatus(accountId, AccountStatus.ACTIVE)).thenReturn(true);

		assertThat(new AccountStatusAuthorizationService(accounts).isActive(accountId)).isTrue();
		verify(accounts).existsByIdAndStatus(accountId, AccountStatus.ACTIVE);
	}

	@Test
	void authenticatesEachApprovedCallingServiceAndMinimizesTheResponse() {
		var service = mock(AccountStatusAuthorizationService.class);
		var accountId = UUID.randomUUID();
		when(service.isActive(accountId)).thenReturn(true);
		var properties = new AccountStatusAuthorizationProperties(TOKEN, COMMUNITY_TOKEN, CONSULTATION_TOKEN);
		var controller = new AccountStatusAuthorizationController(service, properties);

		var credentials = java.util.Map.of("care-service", TOKEN, "community-service", COMMUNITY_TOKEN,
				"consultation-service", CONSULTATION_TOKEN);
		for (var entry : credentials.entrySet()) {
			var response = controller.authorize(accountId, entry.getKey(), entry.getValue());
			assertThat(response.getBody()).isEqualTo(new AccountStatusAuthorizationController.AuthorizationResult(true));
			assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
		}
	}

	@Test
	void rejectsUnknownCallerOrWrongCredential() {
		var controller = new AccountStatusAuthorizationController(mock(AccountStatusAuthorizationService.class),
				new AccountStatusAuthorizationProperties(TOKEN, COMMUNITY_TOKEN, CONSULTATION_TOKEN));

		assertThatThrownBy(() -> controller.authorize(UUID.randomUUID(), "unknown-service", TOKEN))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
		assertThatThrownBy(() -> controller.authorize(UUID.randomUUID(), "care-service", "wrong"))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> new AccountStatusAuthorizationProperties(TOKEN, TOKEN, ""))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
