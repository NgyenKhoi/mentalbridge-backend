package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class InternalNotificationDeliveryControllerTests {

	private static final String TOKEN = "test-notification-service-token-at-least-32-characters";

	@Test
	void returnsOnlyAnActiveVerifiedDeliveryContactWithTheScopedToken() {
		var accountId = UUID.randomUUID();
		var accounts = mock(AccountQueryService.class);
		when(accounts.findById(accountId)).thenReturn(Optional.of(new AccountQueryService.AccountDetail(accountId,
				"owner@example.test", AccountStatus.ACTIVE, RoleCode.USER, Instant.now(), Instant.now(), Instant.now(), 1)));
		var controller = new InternalNotificationDeliveryController(accounts, TOKEN);

		assertThat(controller.deliveryContact(accountId, TOKEN).email()).isEqualTo("owner@example.test");
	}

	@Test
	void rejectsAnInvalidServiceTokenWithoutReturningTheAddress() {
		var controller = new InternalNotificationDeliveryController(mock(AccountQueryService.class), TOKEN);
		assertThatThrownBy(() -> controller.deliveryContact(UUID.randomUUID(), "wrong"))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("401");
	}
}
