package com.mentalbridge.care.resourceeligibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.configuration.ResourceEligibilityClientProperties;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.EligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.RequiredEligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCategory;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityQuery;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityReasonCode;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityResult;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningDomain;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningInstrument;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningLevel;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.SupportTier;

import feign.FeignException;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class ResourceEligibilityClientTests {

	private static final UUID CORRELATION_ID = UUID.fromString("c0000000-0000-4000-8000-000000000001");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T02:00:00Z"), ZoneOffset.UTC);

	@Test
	void forwardsAuthenticationAndCorrelationAndAcceptsExactResponse() {
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> correlation = new AtomicReference<>();
		var client = client((auth, correlationId, request) -> {
			authorization.set(auth);
			correlation.set(correlationId);
			return eligible(request);
		}, properties(2, 5));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(response.results()).hasSize(1);
		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.ELIGIBLE);
		assertThat(authorization).hasValue("Bearer user-jwt");
		assertThat(correlation).hasValue(CORRELATION_ID.toString());
	}

	@Test
	void retriesOneTransientTimeoutAndThenReturnsTheValidatedResponse() {
		AtomicInteger attempts = new AtomicInteger();
		var client = client((auth, correlationId, request) -> {
			if (attempts.incrementAndGet() == 1) {
				throw new RuntimeException(new SocketTimeoutException("synthetic timeout"));
			}
			return eligible(request);
		}, properties(2, 5));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(attempts).hasValue(2);
		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.ELIGIBLE);
	}

	@Test
	void doesNotRetryDeterministicClientErrorsAndFailsClosed() {
		AtomicInteger attempts = new AtomicInteger();
		var client = client((auth, correlationId, request) -> {
			attempts.incrementAndGet();
			throw httpFailure(422);
		}, properties(2, 5));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(attempts).hasValue(1);
		assertThat(response.results().getFirst()).extracting(ResourceEligibilityResult::outcome,
				ResourceEligibilityResult::reasonCode)
				.containsExactly(ResourceEligibilityOutcome.UNAVAILABLE,
						ResourceEligibilityReasonCode.DEPENDENCY_UNAVAILABLE);
	}

	@Test
	void doesNotRetryRateLimitingWithoutAContractedRetryAfterPolicy() {
		AtomicInteger attempts = new AtomicInteger();
		var client = client((auth, correlationId, request) -> {
			attempts.incrementAndGet();
			throw httpFailure(429);
		}, properties(3, 5));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(attempts).hasValue(1);
		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.UNAVAILABLE);
	}

	@Test
	void configuresBoundedExponentialBackoffWithJitterForMultipleRetries() {
		var client = client((auth, correlationId, request) -> eligible(request),
				properties(3, 5, Duration.ofMillis(100)));

		for (int sample = 0; sample < 20; sample++) {
			long firstRetry = client.retryDelayMillis(1);
			long secondRetry = client.retryDelayMillis(2);
			assertThat(firstRetry).isBetween(80L, 120L);
			assertThat(secondRetry).isBetween(160L, 240L).isGreaterThan(firstRetry);
		}
	}

	@Test
	void rejectsMismatchedHealthAttributionAndMalformedEligibility() {
		AtomicInteger attempts = new AtomicInteger();
		var client = client((auth, correlationId, request) -> {
			attempts.incrementAndGet();
			var valid = eligible(request).results().getFirst();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-13T02:00:00Z",
					List.of(new ResourceEligibilityResult(valid.requestId(), UUID.randomUUID().toString(),
							valid.contentVersion(), valid.outcome(), valid.reasonCode(), valid.role(),
							valid.publicationId(), valid.category(), valid.title(), valid.summary(), valid.externalUrl())));
		}, properties(2, 5));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(attempts).hasValue(1);
		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.UNAVAILABLE);
	}

	@Test
	void rejectsAdjunctEligibilityForAPrimaryCoreRequirement() {
		var client = client((auth, correlationId, request) -> {
			var valid = eligible(request).results().getFirst();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-13T02:00:00Z",
					List.of(new ResourceEligibilityResult(valid.requestId(), valid.resourceId(), valid.contentVersion(),
							valid.outcome(), valid.reasonCode(), EligibilityRole.ADJUNCT, valid.publicationId(),
							valid.category(), valid.title(), valid.summary(), valid.externalUrl())));
		}, properties(2, 5));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.UNAVAILABLE);
	}

	@Test
	void rejectsUnsafeDisplaySnapshotUrls() {
		var client = client((auth, correlationId, request) -> {
			var valid = eligible(request).results().getFirst();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-13T02:00:00Z",
					List.of(new ResourceEligibilityResult(valid.requestId(), valid.resourceId(), valid.contentVersion(),
							valid.outcome(), valid.reasonCode(), valid.role(), valid.publicationId(), valid.category(),
							valid.title(), valid.summary(), "javascript:alert(1)")));
		}, properties(2, 5));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.UNAVAILABLE);
	}

	@Test
	void preservesAStaleDecisionAndItsPublicationProvenanceWithoutDisplayCopy() {
		var client = client((auth, correlationId, request) -> {
			var query = request.requests().getFirst();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-13T02:00:00Z",
					List.of(new ResourceEligibilityResult(query.requestId(), query.resourceId(), query.contentVersion(),
							ResourceEligibilityOutcome.STALE, ResourceEligibilityReasonCode.CONTENT_VERSION_STALE,
							null, "30000000-0000-4000-8000-000000000001", null, null, null, null)));
		}, properties(2, 5));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.STALE);
		assertThat(response.results().getFirst().publicationId()).isNotNull();
	}

	@Test
	void opensTheCircuitAfterBoundedDependencyFailures() {
		AtomicInteger calls = new AtomicInteger();
		var client = client((auth, correlationId, request) -> {
			calls.incrementAndGet();
			throw httpFailure(503);
		}, properties(1, 2));

		client.resolve(request(), "user-jwt", CORRELATION_ID);
		client.resolve(request(), "user-jwt", CORRELATION_ID);
		client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(calls).hasValue(2);
		assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void generatedContractToleratesUnknownFieldsButRejectsUnknownEnums() throws Exception {
		var mapper = new ObjectMapper();
		String validWithNewField = responseJson("ELIGIBLE").replace("\"results\":",
				"\"providerExtension\":true,\"results\":");

		var response = mapper.readValue(validWithNewField, ResourceEligibilityBatchResponse.class);
		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.ELIGIBLE);
		assertThatThrownBy(() -> mapper.readValue(responseJson("FUTURE_ELIGIBLE"),
				ResourceEligibilityBatchResponse.class)).hasMessageContaining("FUTURE_ELIGIBLE");
	}

	@Test
	void appliesTheConfiguredConnectionAndReadDeadlinesWithoutFeignRetries() {
		var properties = properties(2, 5);
		var configuration = new ResourceEligibilityFeignConfiguration();

		var options = configuration.resourceEligibilityRequestOptions(properties);

		assertThat(options.connectTimeoutMillis()).isEqualTo(500);
		assertThat(options.readTimeoutMillis()).isEqualTo(2_000);
		assertThat(configuration.resourceEligibilityFeignRetryer()).isSameAs(feign.Retryer.NEVER_RETRY);
	}

	@Test
	void rejectsANonPositiveRetryBaseDelay() {
		assertThatThrownBy(() -> properties(2, 5, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("retryWait must be positive");
	}

	private ResourceEligibilityClient client(ContentResourceEligibilityHttpClient httpClient,
			ResourceEligibilityClientProperties properties) {
		return new ResourceEligibilityClient(httpClient, properties, CLOCK);
	}

	private ResourceEligibilityClientProperties properties(int maxAttempts, int minimumCalls) {
		return properties(maxAttempts, minimumCalls, Duration.ofMillis(1));
	}

	private ResourceEligibilityClientProperties properties(int maxAttempts, int minimumCalls, Duration retryWait) {
		return new ResourceEligibilityClientProperties("", Duration.ofMillis(500), Duration.ofSeconds(2),
				maxAttempts, retryWait, minimumCalls, minimumCalls, 50, Duration.ofSeconds(10), 1);
	}

	private ResourceEligibilityBatchRequest request() {
		return new ResourceEligibilityBatchRequest(List.of(new ResourceEligibilityQuery(
				"10000000-0000-4000-8000-000000000001",
				"20000000-0000-4000-8000-000000000001",
				"3",
				ScreeningDomain.ANXIETY_SYMPTOMS,
				RequiredEligibilityRole.PRIMARY,
				ScreeningInstrument.GAD_7,
				ScreeningLevel.MILD,
				SupportTier.SELF_GUIDED_SUPPORT,
				"vi-VN")));
	}

	private ResourceEligibilityBatchResponse eligible(ResourceEligibilityBatchRequest request) {
		var query = request.requests().getFirst();
		return new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-13T02:00:00Z",
				List.of(new ResourceEligibilityResult(query.requestId(), query.resourceId(), query.contentVersion(),
						ResourceEligibilityOutcome.ELIGIBLE, ResourceEligibilityReasonCode.ELIGIBLE_MATCH,
						EligibilityRole.PRIMARY, "30000000-0000-4000-8000-000000000001",
						ResourceCategory.ARTICLE, "Synthetic title", "Synthetic summary", null)));
	}

	private FeignException httpFailure(int status) {
		Request request = Request.create(Request.HttpMethod.POST, "/internal/v1/resource-eligibility:resolve",
				Map.of(), null, StandardCharsets.UTF_8, null);
		Response response = Response.builder().request(request).status(status).reason("synthetic").headers(Map.of())
				.build();
		return FeignException.errorStatus("resolveResourceEligibility", response);
	}

	private String responseJson(String outcome) {
		return """
				{
				  "policyVersion":"content-eligibility-v1",
				  "resolvedAt":"2026-09-13T02:00:00Z",
				  "results":[{
				    "requestId":"10000000-0000-4000-8000-000000000001",
				    "resourceId":"20000000-0000-4000-8000-000000000001",
				    "contentVersion":"3",
				    "outcome":"%s",
				    "reasonCode":"ELIGIBLE_MATCH",
				    "role":"PRIMARY",
				    "publicationId":"30000000-0000-4000-8000-000000000001",
				    "category":"ARTICLE",
				    "title":"Synthetic title",
				    "summary":"Synthetic summary",
				    "externalUrl":null
				  }]
				}
				""".formatted(outcome);
	}
}
