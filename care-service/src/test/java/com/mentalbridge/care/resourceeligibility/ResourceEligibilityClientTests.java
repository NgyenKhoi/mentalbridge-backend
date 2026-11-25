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
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCurrentVersion;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCurrentVersionBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCurrentVersionBatchResponse;
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
import com.mentalbridge.care.shared.ApiException;

import feign.FeignException;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class ResourceEligibilityClientTests {

	private static final UUID CORRELATION_ID = UUID.fromString("c0000000-0000-4000-8000-000000000001");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T02:00:00Z"), ZoneOffset.UTC);
	private static final String RESOURCE_A = "20000000-0000-4000-8000-000000000001";
	private static final String RESOURCE_B = "20000000-0000-4000-8000-000000000002";

	private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.code()).isEqualTo("RESOURCE_ELIGIBILITY_UNAVAILABLE");
			assertThat(exception.status().value()).isEqualTo(503);
		});
	}

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
	void classifiesAuthenticationRejectionSeparatelyFromDependencyOutage() {
		assertThat(ResourceEligibilityClient.failureCategory(httpFailure(401)))
				.isEqualTo("AUTHENTICATION_REJECTED");
		assertThat(ResourceEligibilityClient.failureCategory(httpFailure(503)))
				.isEqualTo("DEPENDENCY_UNAVAILABLE");
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
	void resolvesCurrentVersionsInRequestOrderWithExplicitMissingResources() {
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> correlation = new AtomicReference<>();
		var client = versionsClient((auth, correlationId, request) -> {
			authorization.set(auth);
			correlation.set(correlationId);
			return versions(new ResourceCurrentVersion(RESOURCE_A, "1"), new ResourceCurrentVersion(RESOURCE_B, null));
		});

		var versions = client.currentVersions(List.of(RESOURCE_A, RESOURCE_B), "user-jwt", CORRELATION_ID);

		assertThat(versions).containsExactly(Map.entry(RESOURCE_A, "1"),
				new java.util.AbstractMap.SimpleEntry<>(RESOURCE_B, null));
		assertThat(authorization).hasValue("Bearer user-jwt");
		assertThat(correlation).hasValue(CORRELATION_ID.toString());
	}

	@Test
	void failsClosedOnMalformedPartialReorderedOrDuplicatedCurrentVersions() {
		var malformed = List.of(
				versions(new ResourceCurrentVersion(RESOURCE_A, "1")),
				versions(new ResourceCurrentVersion(RESOURCE_B, "1"), new ResourceCurrentVersion(RESOURCE_A, "0")),
				versions(new ResourceCurrentVersion(RESOURCE_A, "1"), new ResourceCurrentVersion(RESOURCE_A, "1")),
				versions(new ResourceCurrentVersion(RESOURCE_A, "01"), new ResourceCurrentVersion(RESOURCE_B, "0")),
				versions(new ResourceCurrentVersion(RESOURCE_A, "-1"), new ResourceCurrentVersion(RESOURCE_B, "0")),
				versions(new ResourceCurrentVersion(RESOURCE_A, "9999999999999999999"),
						new ResourceCurrentVersion(RESOURCE_B, "0")),
				versions(new ResourceCurrentVersion(RESOURCE_A, 1), new ResourceCurrentVersion(RESOURCE_B, "0")),
				versions(new ResourceCurrentVersion(RESOURCE_A, "1"), null),
				new ResourceCurrentVersionBatchResponse(null));
		for (var response : malformed) {
			var client = versionsClient((auth, correlationId, request) -> response);
			assertUnavailable(() -> client.currentVersions(List.of(RESOURCE_A, RESOURCE_B), "user-jwt",
					CORRELATION_ID));
		}
	}

	@Test
	void bothJacksonGenerationsRejectAnOmittedVersionAndKeepANumericVersionDistinguishable() throws Exception {
		var type = ResourceCurrentVersionBatchResponse.class;
		String omitted = "{\"results\":[{\"resourceId\":\"%s\"}]}".formatted(RESOURCE_A);
		String explicitNull = "{\"results\":[{\"resourceId\":\"%s\",\"contentVersion\":null,\"x\":1}]}".formatted(RESOURCE_A);
		String numeric = "{\"results\":[{\"resourceId\":\"%s\",\"contentVersion\":1}]}".formatted(RESOURCE_A);
		var jackson2 = new ObjectMapper();
		var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();

		assertThatThrownBy(() -> jackson2.readValue(omitted, type)).hasMessageContaining("contentVersion");
		assertThatThrownBy(() -> jackson3.readValue(omitted, type)).hasMessageContaining("contentVersion");
		for (var decoded : List.of(jackson2.readValue(explicitNull, type), jackson3.readValue(explicitNull, type))) {
			assertThat(decoded.results().getFirst().contentVersion()).isNull();
		}
		for (var decoded : List.of(jackson2.readValue(numeric, type), jackson3.readValue(numeric, type))) {
			assertThat(decoded.results().getFirst().contentVersion()).isNotInstanceOf(String.class);
		}
	}

	@Test
	void retriesATransientCurrentVersionTimeoutButNotADeterministicRejection() {
		AtomicInteger attempts = new AtomicInteger();
		var transientClient = versionsClient((auth, correlationId, request) -> {
			if (attempts.incrementAndGet() == 1) {
				throw new RuntimeException(new SocketTimeoutException("synthetic timeout"));
			}
			return versions(new ResourceCurrentVersion(RESOURCE_A, "0"));
		});
		assertThat(transientClient.currentVersions(List.of(RESOURCE_A), "user-jwt", CORRELATION_ID))
				.containsExactly(Map.entry(RESOURCE_A, "0"));
		assertThat(attempts).hasValue(2);

		for (int status : List.of(401, 403, 422)) {
			AtomicInteger rejected = new AtomicInteger();
			var client = versionsClient((auth, correlationId, request) -> {
				rejected.incrementAndGet();
				throw httpFailure(status);
			});
			assertUnavailable(() -> client.currentVersions(List.of(RESOURCE_A), "user-jwt", CORRELATION_ID));
			assertThat(rejected).hasValue(1);
		}
	}

	@Test
	void rejectsAnUnboundedOrDuplicateCurrentVersionBatchBeforeCallingContent() {
		var client = versionsClient((auth, correlationId, request) -> {
			throw new AssertionError("must not call Content");
		});
		var tooMany = java.util.stream.IntStream.range(0, 51)
				.mapToObj(index -> "20000000-0000-4000-8000-%012d".formatted(index)).toList();
		for (List<String> ids : List.of(List.<String>of(), tooMany, List.of(RESOURCE_A, RESOURCE_A))) {
			assertThatThrownBy(() -> client.currentVersions(ids, "user-jwt", CORRELATION_ID))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void answersAnAllMissingExactBatchLocallyWithoutCallingContent() {
		var client = client((auth, correlationId, request) -> {
			throw new AssertionError("must not call Content");
		}, properties(2, 5));

		var response = client.resolve(new ResourceEligibilityBatchRequest(List.of()), "user-jwt", CORRELATION_ID);

		assertThat(response.policyVersion()).isEqualTo("content-eligibility-v1");
		assertThat(response.results()).isEmpty();
	}

	@Test
	void rejectsANonPositiveRetryBaseDelay() {
		assertThatThrownBy(() -> properties(2, 5, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("retryWait must be positive");
	}

	private ResourceEligibilityClient client(Resolve resolve, ResourceEligibilityClientProperties properties) {
		return client(resolve, (auth, correlationId, request) -> {
			throw new AssertionError("unexpected current-version call");
		}, properties);
	}

	private ResourceEligibilityClient versionsClient(CurrentVersions currentVersions) {
		return client((auth, correlationId, request) -> {
			throw new AssertionError("unexpected eligibility call");
		}, currentVersions, properties(2, 5));
	}

	private ResourceEligibilityClient client(Resolve resolve, CurrentVersions currentVersions,
			ResourceEligibilityClientProperties properties) {
		return new ResourceEligibilityClient(new ContentResourceEligibilityHttpClient() {
			@Override
			public ResourceEligibilityBatchResponse resolve(String authorization, String correlationId,
					ResourceEligibilityBatchRequest request) {
				return resolve.resolve(authorization, correlationId, request);
			}

			@Override
			public ResourceCurrentVersionBatchResponse currentVersions(String authorization, String correlationId,
					ResourceCurrentVersionBatchRequest request) {
				return currentVersions.currentVersions(authorization, correlationId, request);
			}
		}, properties, CLOCK);
	}

	private interface Resolve {
		ResourceEligibilityBatchResponse resolve(String authorization, String correlationId,
				ResourceEligibilityBatchRequest request);
	}

	private interface CurrentVersions {
		ResourceCurrentVersionBatchResponse currentVersions(String authorization, String correlationId,
				ResourceCurrentVersionBatchRequest request);
	}

	private static ResourceCurrentVersionBatchResponse versions(ResourceCurrentVersion... values) {
		return new ResourceCurrentVersionBatchResponse(java.util.Arrays.asList(values));
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
