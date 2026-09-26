package com.mentalbridge.care.supportguide;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.configuration.SupportGuidePhrasingClientProperties;
import com.mentalbridge.care.supportguide.SupportGuidePhrasingClient.PhrasingResponse;

import feign.FeignException;
import feign.Request;
import feign.Response;

class SupportGuidePhrasingClientTests {

	private static final UUID CORRELATION_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

	@Test
	void acceptsValidatedAiPhrasingAndForwardsRequestContext() {
		var authorization = new String[1];
		var correlation = new String[1];
		var client = client((auth, correlationId, request) -> {
			authorization[0] = auth;
			correlation[0] = correlationId;
			return new PhrasingResponse("Diễn giải rõ ràng hơn.", "GEMINI", "gemini-approved",
					"support-guide-phrasing-v1", 1);
		});

		var result = client.phrase("Nội dung đã duyệt.", "user-token", CORRELATION_ID);

		assertThat(result.status()).isEqualTo("STANDARD");
		assertThat(result.text()).isEqualTo("Diễn giải rõ ràng hơn.");
		assertThat(authorization[0]).isEqualTo("Bearer user-token");
		assertThat(correlation[0]).isEqualTo(CORRELATION_ID.toString());
	}

	@Test
	void fallsBackToExactApprovedCopyWhenAiIsUnavailableOrMalformed() {
		var unavailable = client((auth, correlationId, request) -> {
			throw httpFailure(503);
		});
		var malformed = client((auth, correlationId, request) ->
				new PhrasingResponse("", "GEMINI", "gemini-approved", "support-guide-phrasing-v1", 1));

		assertThat(unavailable.phrase("Nội dung đã duyệt.", "user-token", CORRELATION_ID))
				.extracting(SupportGuidePhrasingClient.Phrasing::text,
						SupportGuidePhrasingClient.Phrasing::status)
				.containsExactly("Nội dung đã duyệt.", "AI_UNAVAILABLE_FALLBACK");
		assertThat(malformed.phrase("Nội dung đã duyệt.", "user-token", CORRELATION_ID).status())
				.isEqualTo("AI_UNAVAILABLE_FALLBACK");
	}

	@Test
	void distinguishesAuthenticationRejectionFromProviderOutage() {
		assertThat(SupportGuidePhrasingClient.failureCategory(httpFailure(401)))
				.isEqualTo("AUTHENTICATION_REJECTED");
		assertThat(SupportGuidePhrasingClient.failureCategory(httpFailure(503)))
				.isEqualTo("DEPENDENCY_UNAVAILABLE");
	}

	@Test
	void opensCircuitAfterRepeatedProviderFailures() {
		var client = client((auth, correlationId, request) -> {
			throw httpFailure(503);
		});

		for (int attempt = 0; attempt < 5; attempt++) {
			client.phrase("Approved Care copy.", "user-token", CORRELATION_ID);
		}

		assertThat(client.circuitState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
	}

	private SupportGuidePhrasingClient client(JournalSupportGuidePhrasingHttpClient httpClient) {
		return new SupportGuidePhrasingClient(httpClient,
				new SupportGuidePhrasingClientProperties("", Duration.ofMillis(500), Duration.ofSeconds(8),
						10, 5, 50, Duration.ofSeconds(10), 2));
	}

	private FeignException httpFailure(int status) {
		Request request = Request.create(Request.HttpMethod.POST, "/internal/v1/support-guide-phrasing",
				Map.of(), null, StandardCharsets.UTF_8, null);
		Response response = Response.builder().request(request).status(status).reason("synthetic").headers(Map.of())
				.build();
		return FeignException.errorStatus("phraseSupportGuide", response);
	}
}
