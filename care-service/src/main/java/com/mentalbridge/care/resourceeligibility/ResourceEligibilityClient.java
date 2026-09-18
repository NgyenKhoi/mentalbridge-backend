package com.mentalbridge.care.resourceeligibility;

import java.net.ConnectException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.mentalbridge.care.configuration.ResourceEligibilityClientProperties;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.EligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.RequiredEligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityQuery;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityReasonCode;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityResult;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

@Component
public class ResourceEligibilityClient {

	private static final String POLICY_VERSION = "content-eligibility-v1";

	private final ContentResourceEligibilityHttpClient httpClient;
	private final Clock clock;
	private final CircuitBreaker circuitBreaker;
	private final Retry retry;
	private final IntervalFunction retryInterval;

	public ResourceEligibilityClient(ContentResourceEligibilityHttpClient httpClient,
			ResourceEligibilityClientProperties properties, Clock clock) {
		this.httpClient = httpClient;
		this.clock = clock;
		Predicate<Throwable> transientFailure = ResourceEligibilityClient::isTransientFailure;
		this.retryInterval = IntervalFunction.ofExponentialRandomBackoff(properties.retryWait(), 2.0, 0.2);
		this.retry = Retry.of("contentResourceEligibility", RetryConfig.custom()
				.maxAttempts(properties.maxAttempts())
				.intervalFunction(retryInterval)
				.retryOnException(transientFailure)
				.failAfterMaxAttempts(true)
				.build());
		this.circuitBreaker = CircuitBreaker.of("contentResourceEligibility", CircuitBreakerConfig.custom()
				.slidingWindowSize(properties.circuitWindowSize())
				.minimumNumberOfCalls(properties.circuitMinimumCalls())
				.failureRateThreshold(properties.circuitFailureRate())
				.waitDurationInOpenState(properties.circuitOpenDuration())
				.permittedNumberOfCallsInHalfOpenState(properties.circuitHalfOpenCalls())
				.recordException(ResourceEligibilityClient::countsForCircuit)
				.build());
	}

	public ResourceEligibilityBatchResponse resolve(ResourceEligibilityBatchRequest request, String bearerToken,
			UUID correlationId) {
		validateRequest(request, bearerToken, correlationId);
		Supplier<ResourceEligibilityBatchResponse> remote = CircuitBreaker.decorateSupplier(circuitBreaker,
				() -> validateResponse(request,
						httpClient.resolve("Bearer " + bearerToken, correlationId.toString(), request)));
		try {
			return Retry.decorateSupplier(retry, remote).get();
		}
		catch (RuntimeException exception) {
			return unavailable(request.requests());
		}
	}

	CircuitBreaker.State circuitState() {
		return circuitBreaker.getState();
	}

	long retryDelayMillis(int attempt) {
		return retryInterval.apply(attempt);
	}

	private ResourceEligibilityBatchResponse validateResponse(ResourceEligibilityBatchRequest request,
			ResourceEligibilityBatchResponse response) {
		if (response == null || !POLICY_VERSION.equals(response.policyVersion()) || response.results() == null
				|| response.results().size() != request.requests().size()) {
			throw new MalformedEligibilityResponseException();
		}
		try {
			OffsetDateTime.parse(response.resolvedAt());
		}
		catch (RuntimeException exception) {
			throw new MalformedEligibilityResponseException();
		}
		Set<String> requestIds = new HashSet<>();
		for (int index = 0; index < request.requests().size(); index++) {
			ResourceEligibilityQuery expected = request.requests().get(index);
			ResourceEligibilityResult actual = response.results().get(index);
			if (actual == null || !expected.requestId().equals(actual.requestId())
					|| !expected.resourceId().equals(actual.resourceId())
					|| !expected.contentVersion().equals(actual.contentVersion()) || actual.outcome() == null
					|| actual.reasonCode() == null || actual.outcome() == ResourceEligibilityOutcome.UNAVAILABLE
					|| !requestIds.add(actual.requestId())) {
				throw new MalformedEligibilityResponseException();
			}
			validateDecision(expected, actual);
		}
		return response;
	}

	private void validateDecision(ResourceEligibilityQuery expected, ResourceEligibilityResult result) {
		if (result.outcome() == ResourceEligibilityOutcome.ELIGIBLE) {
			if (result.reasonCode() != ResourceEligibilityReasonCode.ELIGIBLE_MATCH || result.role() == null
					|| result.publicationId() == null || result.category() == null || result.title() == null
					|| result.title().isBlank() || result.title().length() > 255 || result.summary() == null
					|| result.summary().isBlank() || !validExternalUrl(result.externalUrl())) {
				throw new MalformedEligibilityResponseException();
			}
			if (expected.requiredRole() == RequiredEligibilityRole.PRIMARY && result.role() != EligibilityRole.PRIMARY) {
				throw new MalformedEligibilityResponseException();
			}
			try {
				UUID.fromString(result.publicationId());
			}
			catch (IllegalArgumentException exception) {
				throw new MalformedEligibilityResponseException();
			}
		}
		else if (result.role() != null || result.category() != null || result.title() != null
				|| result.summary() != null || result.externalUrl() != null) {
			throw new MalformedEligibilityResponseException();
		}
	}

	private boolean validExternalUrl(String value) {
		if (value == null) return true;
		try {
			URI uri = URI.create(value);
			return value.length() <= 2048 && uri.isAbsolute() && uri.getUserInfo() == null
					&& ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private void validateRequest(ResourceEligibilityBatchRequest request, String bearerToken, UUID correlationId) {
		if (request == null || request.requests() == null || request.requests().isEmpty()
				|| request.requests().size() > 50 || bearerToken == null || bearerToken.isBlank()
				|| correlationId == null) {
			throw new IllegalArgumentException("A bounded eligibility batch and request context are required");
		}
	}

	private ResourceEligibilityBatchResponse unavailable(List<ResourceEligibilityQuery> requests) {
		List<ResourceEligibilityResult> results = requests.stream()
				.map(request -> new ResourceEligibilityResult(request.requestId(), request.resourceId(),
						request.contentVersion(), ResourceEligibilityOutcome.UNAVAILABLE,
						ResourceEligibilityReasonCode.DEPENDENCY_UNAVAILABLE, null, null, null, null, null, null))
				.toList();
		return new ResourceEligibilityBatchResponse(POLICY_VERSION,
				OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).toString(), results);
	}

	private static boolean isTransientFailure(Throwable error) {
		if (hasCause(error, SocketTimeoutException.class) || hasCause(error, ConnectException.class)
				|| error instanceof RetryableException) {
			return true;
		}
		if (error instanceof FeignException exception) {
			return exception.status() == 408 || exception.status() >= 500;
		}
		return false;
	}

	private static boolean countsForCircuit(Throwable error) {
		return error instanceof MalformedEligibilityResponseException || isTransientFailure(error);
	}

	private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		Throwable current = error;
		while (current != null) {
			if (type.isInstance(current)) return true;
			current = current.getCause();
		}
		return false;
	}

	private static final class MalformedEligibilityResponseException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
