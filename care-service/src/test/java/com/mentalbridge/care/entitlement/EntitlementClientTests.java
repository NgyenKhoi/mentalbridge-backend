package com.mentalbridge.care.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.EntitlementSource;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.shared.ApiException;

class EntitlementClientTests {

	private final ConsultationEntitlementHttpClient http = mock(ConsultationEntitlementHttpClient.class);
	private final EntitlementClient client = new EntitlementClient(http);

	@Test
	void acceptsAuthoritativePaidAndDefaultFreeDecisionsForTheSameAccount() {
		var userId = UUID.randomUUID();
		when(http.current("Bearer token", "00000000-0000-4000-8000-000000000001"))
				.thenReturn(paid(userId));

		assertThat(client.current(userId, "token", UUID.fromString("00000000-0000-4000-8000-000000000001"))
				.packageCode()).isEqualTo(ServicePackage.PLUS);

		when(http.current("Bearer token", "00000000-0000-4000-8000-000000000002"))
				.thenReturn(new CurrentEntitlementResponse(userId, ServicePackage.FREE,
						EntitlementSource.DEFAULT_FREE, null, null, null, "service-entitlement-v1", 0,
						Instant.parse("2026-09-19T00:00:00Z")));
		assertThat(client.current(userId, "token", UUID.fromString("00000000-0000-4000-8000-000000000002"))
				.packageCode()).isEqualTo(ServicePackage.FREE);
	}

	@Test
	void failsClosedForWrongOwnerMalformedPaidEvidenceAndProviderFailure() {
		var userId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		when(http.current("Bearer token", correlationId.toString())).thenReturn(paid(UUID.randomUUID()));
		assertUnavailable(() -> client.current(userId, "token", correlationId));

		when(http.current("Bearer token", correlationId.toString())).thenReturn(new CurrentEntitlementResponse(userId,
				ServicePackage.PREMIUM, EntitlementSource.DEFAULT_FREE, null, null, null,
				"service-entitlement-v1", 0, Instant.parse("2026-09-19T00:00:00Z")));
		assertUnavailable(() -> client.current(userId, "token", correlationId));

		when(http.current("Bearer token", correlationId.toString())).thenThrow(new RuntimeException("unavailable"));
		assertUnavailable(() -> client.current(userId, "token", correlationId));
	}

	private CurrentEntitlementResponse paid(UUID userId) {
		return new CurrentEntitlementResponse(userId, ServicePackage.PLUS, EntitlementSource.DEMO, "test-demo",
				Instant.parse("2026-09-18T00:00:00Z"), Instant.parse("2026-10-18T00:00:00Z"),
				"service-entitlement-v1", 1, Instant.parse("2026-09-19T00:00:00Z"));
	}

	private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("ENTITLEMENT_UNAVAILABLE"));
	}
}
