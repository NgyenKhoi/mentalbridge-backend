package com.mentalbridge.care.supportplan;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.configuration.ResourceEvidenceProperties;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.ResourceEvidenceHttpClient.Evidence;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

@Component
public class ResourceEvidenceClient {
	private final ResourceEvidenceHttpClient http;
	private final Clock clock;
	private final CircuitBreaker circuitBreaker;
	private final Retry retry;

	public ResourceEvidenceClient(ResourceEvidenceHttpClient http,
			ResourceEvidenceProperties properties, Clock clock) {
		this.http = http;
		this.clock = clock;
		this.retry = Retry.of("resourceEvidence", RetryConfig.custom()
				.maxAttempts(properties.maxAttempts())
				.intervalFunction(IntervalFunction.ofExponentialRandomBackoff(properties.retryWait(), 2.0, 0.2))
				.retryOnException(ResourceEvidenceClient::transientFailure).build());
		this.circuitBreaker = CircuitBreaker.of("resourceEvidence", CircuitBreakerConfig.custom()
				.slidingWindowSize(properties.circuitWindowSize())
				.minimumNumberOfCalls(properties.circuitMinimumCalls())
				.failureRateThreshold(properties.circuitFailureRate())
				.waitDurationInOpenState(properties.circuitOpenDuration())
				.permittedNumberOfCallsInHalfOpenState(properties.circuitHalfOpenCalls())
				.recordException(error -> error instanceof InvalidEvidenceException || transientFailure(error))
				.build());
	}

	public Evidence read(UUID resourceId, String resourceVersion, LocalDate localDate, String timeZone,
			List<String> excludedFactIds, String bearerToken, UUID correlationId) {
		Supplier<Evidence> remote = CircuitBreaker.decorateSupplier(circuitBreaker,
				() -> validate(http.read("Bearer " + bearerToken, correlationId.toString(), resourceId,
						resourceVersion, localDate, timeZone, excludedFactIds), resourceVersion));
		try {
			return Retry.decorateSupplier(retry, remote).get();
		}
		catch (FeignException.NotFound exception) {
			return null;
		}
		catch (RuntimeException exception) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RESOURCE_EVIDENCE_UNAVAILABLE",
					"Resource completion evidence is temporarily unavailable; no completion was recorded");
		}
	}

	CircuitBreaker.State circuitState() {
		return circuitBreaker.getState();
	}

	private Evidence validate(Evidence value, String resourceVersion) {
		if (value == null || !Set.of("RESOURCE_PRACTICE_SESSION", "RESOURCE_COMPLETION").contains(value.factKind())
				|| value.factId() == null || value.factId().isBlank() || value.factId().length() > 255
				|| !resourceVersion.equals(value.resourceVersion()) || value.occurredAt() == null
				|| value.verifiedAt() == null || value.verifiedAt().isAfter(clock.instant())
				|| value.occurredAt().isAfter(value.verifiedAt())) {
			throw new InvalidEvidenceException();
		}
		return value;
	}

	private static boolean transientFailure(Throwable failure) {
		return failure instanceof RetryableException || failure instanceof FeignException exception
				&& (exception.status() == 408 || exception.status() >= 500);
	}

	private static final class InvalidEvidenceException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
