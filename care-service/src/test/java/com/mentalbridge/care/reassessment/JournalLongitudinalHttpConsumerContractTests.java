package com.mentalbridge.care.reassessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.configuration.JournalLongitudinalClientProperties;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import feign.Feign;
import feign.Retryer;
import feign.jackson.JacksonDecoder;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class JournalLongitudinalHttpConsumerContractTests {

	private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID ANALYSIS_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID CORRELATION_ID = UUID.fromString("c0000000-0000-4000-8000-000000000001");
	private static final Period PREVIOUS = new Period(Instant.parse("2026-09-01T00:00:00Z"),
			Instant.parse("2026-09-08T00:00:00Z"));
	private static final Period CURRENT = new Period(Instant.parse("2026-09-08T00:00:00Z"),
			Instant.parse("2026-09-15T00:00:00Z"));
	private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
	private static OpenAPI providerContract;

	private HttpServer server;
	private Stub stub;

	@BeforeAll
	static void loadCanonicalProviderContract() {
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var contract = Path.of("..", "contracts", "openapi", "journal-ai-service-v1.yaml").toAbsolutePath();
		var result = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, options);
		assertThat(result.getMessages()).isEmpty();
		providerContract = result.getOpenAPI();
		assertThat(providerContract).isNotNull();
	}

	@BeforeEach
	void startProviderStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		stub = new Stub();
		server.createContext("/internal/v1/users/" + USER_ID + "/longitudinal-analyses/" + ANALYSIS_ID,
				stub::handle);
		server.start();
	}

	@AfterEach
	void stopProviderStub() {
		server.stop(0);
	}

	@Test
	void sendsCanonicalRequestAndDecodesContractResponse() {
		stub.respond(200, validEvidence());

		var projection = client().read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-jwt", CORRELATION_ID);

		assertThat(projection.state()).isEqualTo("AVAILABLE");
		assertThat(projection.evidence().analysisId()).isEqualTo(ANALYSIS_ID);
		assertThat(stub.authorization()).isEqualTo("Bearer user-jwt");
		assertThat(stub.correlationId()).isEqualTo(CORRELATION_ID.toString());
		assertThat(stub.query()).isEqualTo("purpose=REASSESSMENT_SUMMARY");
		assertThat(stub.method()).isEqualTo("GET");
	}

	@Test
	void mapsDocumentedMissingAndConsentResponsesToSafeFallbacks() {
		stub.respond(404, problem(404));
		assertThat(client().read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-jwt", CORRELATION_ID)
				.unavailableReason()).isEqualTo("SOURCE_NOT_FOUND");

		stub.respond(403, problem(403));
		assertThat(client().read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-jwt", CORRELATION_ID)
				.unavailableReason()).isEqualTo("CONSENT_UNAVAILABLE");
	}

	@Test
	void failsClosedWhenContractResponseCannotBeDecoded() {
		stub.respondRaw(200, "{not-json");

		var projection = client().read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-jwt", CORRELATION_ID);

		assertThat(projection.state()).isEqualTo("UNAVAILABLE");
		assertThat(projection.unavailableReason()).isEqualTo("DEPENDENCY_UNAVAILABLE");
	}

	private JournalLongitudinalClient client() {
		var properties = new JournalLongitudinalClientProperties("", Duration.ofMillis(100), Duration.ofSeconds(1),
				1, Duration.ofMillis(1), 10, 5, 50, Duration.ofSeconds(10), 2);
		var configuration = new JournalLongitudinalFeignConfiguration();
		var httpClient = Feign.builder()
				.contract(new SpringMvcContract())
				.decoder(new JacksonDecoder(MAPPER))
				.options(configuration.journalLongitudinalRequestOptions(properties))
				.retryer(Retryer.NEVER_RETRY)
				.target(JournalLongitudinalHttpClient.class, baseUrl());
		return new JournalLongitudinalClient(httpClient, properties);
	}

	private String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static String validEvidence() {
		return """
				{
				  "analysisId":"20000000-0000-4000-8000-000000000001",
				  "previousPeriod":{"startAt":"2026-09-01T00:00:00Z","endAt":"2026-09-08T00:00:00Z"},
				  "currentPeriod":{"startAt":"2026-09-08T00:00:00Z","endAt":"2026-09-15T00:00:00Z"},
				  "sourceJournalRevisions":[
				    {"journalId":"30000000-0000-4000-8000-000000000001","journalRevision":1,"period":"PREVIOUS"},
				    {"journalId":"30000000-0000-4000-8000-000000000002","journalRevision":1,"period":"PREVIOUS"},
				    {"journalId":"30000000-0000-4000-8000-000000000003","journalRevision":1,"period":"PREVIOUS"},
				    {"journalId":"30000000-0000-4000-8000-000000000004","journalRevision":1,"period":"CURRENT"},
				    {"journalId":"30000000-0000-4000-8000-000000000005","journalRevision":1,"period":"CURRENT"},
				    {"journalId":"30000000-0000-4000-8000-000000000006","journalRevision":1,"period":"CURRENT"}
				  ],
				  "contextSignals":["workload"],
				  "emotionIndicators":["anxious"],
				  "recurringThemes":["sleep"],
				  "changesComparedWithPreviousPeriod":[{"signal":"sleep","direction":"LESS_FREQUENT"}],
				  "preferences":[],
				  "barriers":[],
				  "helpfulPatterns":["walk"],
				  "dataCoverage":{"previousPeriodJournalEntryCount":3,"currentPeriodJournalEntryCount":3,"sufficientForComparison":true},
				  "provider":"DETERMINISTIC_FAKE",
				  "model":"fixture-v1",
				  "promptVersion":"longitudinal-v1",
				  "schemaVersion":1,
				  "createdAt":"2026-09-15T00:05:00Z"
				}
				""";
	}

	private static String problem(int status) {
		return """
				{"type":"about:blank","title":"Synthetic provider problem","status":%d,
				 "code":"SYNTHETIC_PROVIDER_PROBLEM","correlationId":"c0000000-0000-4000-8000-000000000001"}
				""".formatted(status);
	}

	private static void assertContractResponse(int status, String body) {
		var operation = providerContract.getPaths()
				.get("/internal/v1/users/{userId}/longitudinal-analyses/{analysisId}")
				.getGet();
		assertThat(operation.getResponses()).containsKey(String.valueOf(status));
		try {
			JsonNode payload = MAPPER.readTree(body);
			if (status == 200) {
				assertRequired(payload, "LongitudinalEvidence");
				assertRequired(payload.path("previousPeriod"), "LongitudinalPeriod");
				assertRequired(payload.path("currentPeriod"), "LongitudinalPeriod");
				assertRequired(payload.path("dataCoverage"), "LongitudinalDataCoverage");
			}
			else {
				assertRequired(payload, "Problem");
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

	private final class Stub {
		private final AtomicInteger status = new AtomicInteger();
		private final AtomicReference<String> body = new AtomicReference<>();
		private final AtomicReference<String> authorization = new AtomicReference<>();
		private final AtomicReference<String> correlationId = new AtomicReference<>();
		private final AtomicReference<String> query = new AtomicReference<>();
		private final AtomicReference<String> method = new AtomicReference<>();

		void respond(int responseStatus, String responseBody) {
			assertContractResponse(responseStatus, responseBody);
			respondRaw(responseStatus, responseBody);
		}

		void respondRaw(int responseStatus, String responseBody) {
			status.set(responseStatus);
			body.set(responseBody);
		}

		void handle(HttpExchange exchange) throws IOException {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			correlationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
			query.set(exchange.getRequestURI().getQuery());
			method.set(exchange.getRequestMethod());
			byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type",
					status.get() == 200 ? "application/json" : "application/problem+json");
			exchange.sendResponseHeaders(status.get(), bytes.length);
			try (var output = exchange.getResponseBody()) {
				output.write(bytes);
			}
		}

		String authorization() {
			return authorization.get();
		}

		String correlationId() {
			return correlationId.get();
		}

		String query() {
			return query.get();
		}

		String method() {
			return method.get();
		}
	}
}
