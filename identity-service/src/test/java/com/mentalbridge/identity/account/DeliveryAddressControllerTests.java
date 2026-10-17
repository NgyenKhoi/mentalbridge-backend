package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class DeliveryAddressControllerTests {

	private static final String TOKEN = "appointment-reminder-service-token-123";
	private static final Instant NOW = Instant.parse("2027-01-02T00:00:00Z");

	@Mock
	private AccountRepository accounts;

	@Test
	void returnsOnlyTheVerifiedAddressForAnActiveUser() {
		var account = AccountEntity.pending("member@example.com", "hash", RoleCode.USER, NOW);
		account.activate(NOW);
		when(accounts.findById(account.id())).thenReturn(Optional.of(account));
		var controller = new DeliveryAddressController(accounts, new DeliveryAddressProperties(TOKEN));

		var result = controller.get(account.id(), TOKEN);

		assertThat(result.accountId()).isEqualTo(account.id());
		assertThat(result.email()).isEqualTo("member@example.com");
	}

	@Test
	void rejectsAnInvalidServiceCredentialBeforeReadingTheAccount() {
		var controller = new DeliveryAddressController(accounts, new DeliveryAddressProperties(TOKEN));

		assertThatThrownBy(() -> controller.get(UUID.randomUUID(), "wrong-token"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
	}

	@Test
	void hidesPendingAndNonUserAccountsAsNotFound() {
		var pending = AccountEntity.pending("pending@example.com", "hash", RoleCode.USER, NOW);
		when(accounts.findById(pending.id())).thenReturn(Optional.of(pending));
		var controller = new DeliveryAddressController(accounts, new DeliveryAddressProperties(TOKEN));

		assertThatThrownBy(() -> controller.get(pending.id(), TOKEN))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
	}
}
