package com.mentalbridge.care.configuration;

import java.time.Duration;
import java.util.UUID;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.stereotype.Component;

@Component
public class IdentityAccountStatusVerifier {
	private final IdentityAccountStatusHttpClient client;
	private final IdentityAccountStatusProperties properties;
	private final CircuitBreaker breaker = CircuitBreaker.of("careIdentityAccountStatus",
			CircuitBreakerConfig.custom().slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
					.waitDurationInOpenState(Duration.ofSeconds(5)).permittedNumberOfCallsInHalfOpenState(1).build());

	public IdentityAccountStatusVerifier(IdentityAccountStatusHttpClient client, IdentityAccountStatusProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	public boolean isActive(UUID accountId, UUID correlationId) {
		if (properties.serviceToken().length() < 32) throw new IdentityAccountStatusUnavailableException();
		try {
			return breaker.executeSupplier(() -> {
				var result = client.authorize(accountId, "care-service", properties.serviceToken(), correlationId.toString());
				if (result == null || result.active() == null) throw new IdentityAccountStatusUnavailableException();
				return result.active();
			});
		}
		catch (RuntimeException exception) { throw new IdentityAccountStatusUnavailableException(); }
	}

	static final class IdentityAccountStatusUnavailableException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
