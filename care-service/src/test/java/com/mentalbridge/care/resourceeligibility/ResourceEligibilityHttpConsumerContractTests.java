package com.mentalbridge.care.resourceeligibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.configuration.ResourceEligibilityClientProperties;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.RequiredEligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityQuery;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningDomain;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningInstrument;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningLevel;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.SupportTier;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import feign.Feign;
import feign.Retryer;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class ResourceEligibilityHttpConsumerContractTests {

	private static final UUID CORRELATION_ID = UUID.fromString("c0000000-0000-4000-8000-000000000001");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T02:00:00Z"), ZoneOffset.UTC);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static OpenAPI providerContract;

	private HttpServer server;
	private ContractStub stub;

	@BeforeAll
	static void loadCanonicalProviderContract() {
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var contract = Path.of("..", "contracts", "openapi", "content-notification-service.yaml").toAbsolutePath();
		var result = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, options);
		assertThat(result.getMessages()).isEmpty();
		providerContract = result.getOpenAPI();
		assertThat(providerContract).isNotNull();
	}

	@BeforeEach
	void startProviderStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		stub = new ContractStub();
		server.createContext("/internal/v1/resource-eligibility:resolve", stub::handle);
		server.start();
	}

	@AfterEach
	void stopProviderStub() {
		server.stop(0);
	}

	@Test
	void sendsTheGeneratedRequestThroughFeignAndDecodesAContractValidatedResponse() throws Exception {
		stub.enqueueContract(200, validResponseWithUnknownFields());
		var client = client(baseUrl(), properties(1, Duration.ofMillis(50), Duration.ofSeconds(1)));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.ELIGIBLE);
		assertThat(stub.authorization()).isEqualTo("Bearer user-jwt");
		assertThat(stub.correlationId()).isEqualTo(CORRELATION_ID.toString());
		assertThat(stub.method()).isEqualTo("POST");
		JsonNode requestBody = MAPPER.readTree(stub.requestBody());
		assertRequired(requestBody, "ResourceEligibilityBatchRequest");
		JsonNode query = requestBody.path("requests").get(0);
		assertRequired(query, "ResourceEligibilityQuery");
		assertEnum(query.path("targetDomain").asText(), "ScreeningDomain");
		assertEnum(query.path("requiredRole").asText(), "RequiredEligibilityRole");
		assertEnum(query.path("instrument").asText(), "ScreeningInstrument");
		assertEnum(query.path("screeningLevel").asText(), "ScreeningLevel");
		assertEnum(query.path("supportTier").asText(), "SupportTier");
		assertThat(query.path("resourceId").asText())
				.isEqualTo("20000000-0000-4000-8000-000000000001");
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 401, 403, 422 })
	void handlesEveryDocumentedClientProblemWithoutRetry(int status) {
		stub.enqueueContract(status, problem(status));
		var client = client(baseUrl(), properties(2, Duration.ofMillis(10), Duration.ofSeconds(1)));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.UNAVAILABLE);
		assertThat(stub.attempts()).isEqualTo(1);
	}

	@Test
	void retriesTheDocumentedServiceUnavailableResponseAndDecodesRecovery() {
		stub.enqueueContract(503, problem(503));
		stub.enqueueContract(200, validResponseWithUnknownFields());
		var client = client(baseUrl(), properties(2, Duration.ofMillis(10), Duration.ofSeconds(1)));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.ELIGIBLE);
		assertThat(stub.attempts()).isEqualTo(2);
	}

	@Test
	void doesNotRetryAnUndocumentedRateLimitResponseWithoutRetryAfterSemantics() {
		stub.enqueueRaw(429, problem(429), Duration.ZERO);
		var client = client(baseUrl(), properties(3, Duration.ofMillis(10), Duration.ofSeconds(1)));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.UNAVAILABLE);
		assertThat(stub.attempts()).isEqualTo(1);
	}

	@Test
	void failsClosedOnAReadTimeout() {
		stub.enqueueContract(200, validResponseWithUnknownFields(), Duration.ofMillis(250));
		var client = client(baseUrl(), properties(1, Duration.ofMillis(50), Duration.ofMillis(50)));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.UNAVAILABLE);
		assertThat(stub.attempts()).isEqualTo(1);
	}

	@Test
	void failsClosedOnConnectionRefusal() {
		String unavailableUrl = baseUrl();
		server.stop(0);
		var client = client(unavailableUrl, properties(1, Duration.ofMillis(50), Duration.ofMillis(50)));

		var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

		assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.UNAVAILABLE);
	}

	@Test
	void failsClosedOnMalformedTruncatedNullMissingAndUnknownEnumResponses() {
		for (String invalid : List.of(
				"not-json",
				"{",
				validResponse().replace("\"outcome\":\"ELIGIBLE\",", ""),
				validResponse().replace("\"outcome\":\"ELIGIBLE\"", "\"outcome\":null"),
				validResponse().replace("ELIGIBLE", "FUTURE_ELIGIBLE"),
				validResponse().replace(
						"\"publicationId\":\"30000000-0000-4000-8000-000000000001\"",
						"\"publicationId\":null"))) {
			stub.enqueueRaw(200, invalid, Duration.ZERO);
			var client = client(baseUrl(), properties(1, Duration.ofMillis(10), Duration.ofSeconds(1)));

			var response = client.resolve(request(), "user-jwt", CORRELATION_ID);

			assertThat(response.results().getFirst().outcome()).isEqualTo(ResourceEligibilityOutcome.UNAVAILABLE);
		}
		assertThat(stub.attempts()).isEqualTo(6);
	}

	private ResourceEligibilityClient client(String baseUrl, ResourceEligibilityClientProperties properties) {
		var configuration = new ResourceEligibilityFeignConfiguration();
		var httpClient = Feign.builder()
				.contract(new SpringMvcContract())
				.encoder(new JacksonEncoder(MAPPER))
				.decoder(new JacksonDecoder(MAPPER))
				.options(configuration.resourceEligibilityRequestOptions(properties))
				.retryer(Retryer.NEVER_RETRY)
				.target(ContentResourceEligibilityHttpClient.class, baseUrl);
		return new ResourceEligibilityClient(httpClient, properties, CLOCK);
	}

	private ResourceEligibilityClientProperties properties(int maxAttempts, Duration connectTimeout,
			Duration readTimeout) {
		return new ResourceEligibilityClientProperties("", connectTimeout, readTimeout, maxAttempts,
				Duration.ofMillis(1), 100, 100, 50, Duration.ofSeconds(10), 1);
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

	private String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static String validResponseWithUnknownFields() {
		return validResponse().replace("\"results\":", "\"providerExtension\":true,\"results\":")
				.replace("\"publicationId\":", "\"futureResultField\":\"ignored\",\"publicationId\":");
	}

	private static String validResponse() {
		return """
				{
				  "policyVersion":"content-eligibility-v1",
				  "resolvedAt":"2026-09-14T02:00:00Z",
				  "results":[{
				    "requestId":"10000000-0000-4000-8000-000000000001",
				    "resourceId":"20000000-0000-4000-8000-000000000001",
				    "contentVersion":"3",
				    "outcome":"ELIGIBLE",
				    "reasonCode":"ELIGIBLE_MATCH",
				    "role":"PRIMARY",
				    "publicationId":"30000000-0000-4000-8000-000000000001"
				  }]
				}
				""";
	}

	private static String problem(int status) {
		return """
				{
				  "type":"https://mentalbridge.io/errors/SYNTHETIC_PROVIDER_PROBLEM",
				  "title":"Synthetic provider problem",
				  "status":%d,
				  "code":"SYNTHETIC_PROVIDER_PROBLEM",
				  "correlationId":"c0000000-0000-4000-8000-000000000001"
				}
				""".formatted(status);
	}

	private static void assertContractResponse(int status, String body) {
		var operation = providerContract.getPaths()
				.get("/internal/v1/resource-eligibility:resolve")
				.getPost();
		assertThat(operation.getResponses()).containsKey(String.valueOf(status));
		try {
			JsonNode payload = MAPPER.readTree(body);
			if (status == 200) {
				assertRequired(payload, "ResourceEligibilityBatchResponse");
				for (JsonNode result : payload.path("results")) {
					assertRequired(result, "ResourceEligibilityResult");
					assertEnum(result.path("outcome").asText(), "ResourceEligibilityOutcome");
					assertEnum(result.path("reasonCode").asText(), "ResourceEligibilityReasonCode");
					if (!result.path("role").isNull()) {
						assertEnum(result.path("role").asText(), "EligibilityRole");
					}
				}
			}
			else {
				assertRequired(payload, "ProblemDetails");
				assertThat(payload.path("status").asInt()).isEqualTo(status);
			}
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("Contract fixture must be valid JSON", exception);
		}
	}

	private static void assertRequired(JsonNode payload, String schemaName) {
		Schema<?> schema = providerContract.getComponents().getSchemas().get(schemaName);
		assertThat(schema).isNotNull();
		for (String field : schema.getRequired()) {
			assertThat(payload.has(field) && !payload.get(field).isNull())
					.as("required %s.%s", schemaName, field)
					.isTrue();
		}
	}

	private static void assertEnum(String value, String schemaName) {
		Schema<?> schema = providerContract.getComponents().getSchemas().get(schemaName);
		assertThat(schema).isNotNull();
		assertThat(schema.getEnum().stream().map(String::valueOf)).contains(value);
	}

	private static final class ContractStub {
		private final ConcurrentLinkedQueue<StubResponse> responses = new ConcurrentLinkedQueue<>();
		private final AtomicInteger attempts = new AtomicInteger();
		private final AtomicReference<String> authorization = new AtomicReference<>();
		private final AtomicReference<String> correlationId = new AtomicReference<>();
		private final AtomicReference<String> method = new AtomicReference<>();
		private final AtomicReference<String> requestBody = new AtomicReference<>();

		void enqueueContract(int status, String body) {
			enqueueContract(status, body, Duration.ZERO);
		}

		void enqueueContract(int status, String body, Duration delay) {
			assertContractResponse(status, body);
			enqueueRaw(status, body, delay);
		}

		void enqueueRaw(int status, String body, Duration delay) {
			responses.add(new StubResponse(status, body, delay));
		}

		void handle(HttpExchange exchange) throws IOException {
			attempts.incrementAndGet();
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			correlationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
			method.set(exchange.getRequestMethod());
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			StubResponse response = responses.remove();
			try {
				Thread.sleep(response.delay().toMillis());
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException(exception);
			}
			byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type",
					response.status() == 200 ? "application/json" : "application/problem+json");
			exchange.sendResponseHeaders(response.status(), bytes.length);
			try (var output = exchange.getResponseBody()) {
				output.write(bytes);
			}
		}

		int attempts() {
			return attempts.get();
		}

		String authorization() {
			return authorization.get();
		}

		String correlationId() {
			return correlationId.get();
		}

		String method() {
			return method.get();
		}

		String requestBody() {
			return requestBody.get();
		}
	}

	private record StubResponse(int status, String body, Duration delay) {
	}
}
