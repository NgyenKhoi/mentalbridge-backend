package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.configuration.EmotionCheckInEvidenceProperties;
import com.mentalbridge.care.shared.ApiException;
import com.sun.net.httpserver.HttpServer;

import feign.Feign;
import feign.Retryer;
import feign.jackson.JacksonDecoder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class EmotionCheckInEvidenceHttpConsumerContractTests {
	private static final UUID USER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID FACT = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID CORRELATION = UUID.fromString("30000000-0000-4000-8000-000000000001");
	private static final LocalDate DATE = LocalDate.parse("2026-10-13");
	private static final Instant NOW = Instant.parse("2026-10-13T08:00:00Z");
	private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
	private static OpenAPI provider;
	private HttpServer server;
	private ExecutorService executor;
	private int status;
	private String body;
	private long delayMillis;
	private String authorization;
	private String correlation;
	private String method;
	private String query;
	private final AtomicInteger attempts = new AtomicInteger();

	@BeforeAll
	static void parseCanonicalProviderAndCareContracts() {
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		for (String filename : new String[] { "journal-ai-service-v1.yaml", "care-service-v1.yaml" }) {
			var path = Path.of("..", "contracts", "openapi", filename).toAbsolutePath();
			var result = new OpenAPIV3Parser().readLocation(path.toUri().toString(), null, options);
			assertThat(result.getMessages()).isEmpty();
			assertThat(result.getOpenAPI()).isNotNull();
			if (filename.startsWith("journal")) provider = result.getOpenAPI();
			else {
				var operation = result.getOpenAPI().getPaths()
						.get("/api/v2/support-plan-occurrences/{occurrenceId}/emotion-check-in/reconcile").getPost();
				assertThat(operation.getRequestBody()).isNull();
				assertThat(operation.getSecurity()).isNotEmpty();
				assertThat(operation.getResponses()).containsKeys("200", "400", "401", "403", "404", "409", "503");
			}
		}
	}

	@BeforeEach
	void startStub() throws Exception {
		status = 200;
		body = MAPPER.writeValueAsString(recorded());
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newCachedThreadPool();
		server.setExecutor(executor);
		server.createContext("/api/v1/emotion-check-ins/" + DATE + "/completion-evidence", exchange -> {
			attempts.incrementAndGet();
			authorization = exchange.getRequestHeaders().getFirst("Authorization");
			correlation = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
			method = exchange.getRequestMethod();
			query = exchange.getRequestURI().getRawQuery();
			try {
				if (delayMillis > 0) Thread.sleep(delayMillis);
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", status == 200 ? "application/json" : "application/problem+json");
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			finally {
				exchange.close();
			}
		});
		server.start();
	}

	@AfterEach
	void stopStub() {
		server.stop(0);
		executor.shutdownNow();
	}

	@Test
	void readsTheCanonicalMinimizedProjectionWithVerifiedOwnerContextOnly() {
		var operation = provider.getPaths().get("/api/v1/emotion-check-ins/{localDate}/completion-evidence").getGet();
		assertThat(operation.getSecurity() == null ? provider.getSecurity() : operation.getSecurity()).isNotEmpty();
		assertThat(operation.getResponses()).containsKeys("200", "400", "401", "503");
		var schema = provider.getComponents().getSchemas().get("EmotionCheckInCompletionEvidence");
		assertThat(schema.getProperties().keySet()).containsExactlyInAnyOrder("schemaVersion", "subjectUserId",
				"localDate", "state", "fact", "verifiedAt");
		var value = client(1, 5).read(USER, DATE, "synthetic-token", CORRELATION);
		assertThat(value.state()).isEqualTo("RECORDED");
		assertThat(value.fact().factId()).isEqualTo(FACT);
		assertThat(value.fact().factRevision()).isEqualTo("1");
		assertThat(authorization).isEqualTo("Bearer synthetic-token");
		assertThat(correlation).isEqualTo(CORRELATION.toString());
		assertThat(method).isEqualTo("GET");
		assertThat(query).isNull();
		assertThat(attempts.get()).isEqualTo(1);
	}

	@Test
	void missingAndDeletedAreAuthoritative200StatesWithNoFact() throws Exception {
		for (String state : new String[] { "MISSING", "DELETED" }) {
			var value = recorded();
			value.put("state", state);
			value.put("fact", null);
			body = MAPPER.writeValueAsString(value);
			assertThat(client(1, 5).read(USER, DATE, "token", CORRELATION).state()).isEqualTo(state);
		}
	}

	@Test
	void authorizationAndValidationFailuresAreNotRetriedOrMisreportedAsMissing() {
		for (int code : new int[] { 400, 401, 403, 404, 409, 429 }) {
			status = code;
			body = "{\"status\":" + code + ",\"code\":\"SAFE_ERROR\"}";
			int before = attempts.get();
			assertUnavailable(client(2, 5));
			assertThat(attempts.get()).isEqualTo(before + 1);
		}
	}

	@Test
	void dependencyFailureRetriesOnlyTheBoundedReadThenReturnsUnavailable() {
		status = 503;
		body = "{\"status\":503,\"code\":\"DEPENDENCY_UNAVAILABLE\"}";
		assertUnavailable(client(2, 5));
		assertThat(attempts.get()).isEqualTo(2);
	}

	@Test
	void mismatchedSubjectDateUnknownStateAndInvalidFactFailClosedWithoutRetry() throws Exception {
		for (String field : new String[] { "subjectUserId", "localDate", "state", "verifiedAt", "schemaVersion" }) {
			var value = recorded();
			value.put(field, switch (field) {
				case "subjectUserId" -> UUID.randomUUID().toString();
				case "localDate" -> DATE.minusDays(1).toString();
				case "state" -> "UNKNOWN";
				case "verifiedAt" -> NOW.plusSeconds(10).toString();
				default -> "future-schema-v9";
			});
			body = MAPPER.writeValueAsString(value);
			int before = attempts.get();
			assertUnavailable(client(2, 5));
			assertThat(attempts.get()).isEqualTo(before + 1);
		}
	}

	@Test
	void malformedJsonAndContentBearingUnknownFieldsNeverBecomePersistedEvidence() throws Exception {
		body = "{malformed";
		assertUnavailable(client(1, 5));
		var value = recorded();
		value.put("privateNote", "must not be represented in the consumer record");
		body = MAPPER.writeValueAsString(value);
		var decoded = client(1, 5).read(USER, DATE, "token", CORRELATION);
		assertThat(MAPPER.writeValueAsString(decoded)).doesNotContain("privateNote", "must not be represented");
	}

	@Test
	void inconsistentFactMetadataAndAbsentStateWithFactAreUnavailable() throws Exception {
		for (String field : new String[] { "ownerDomain", "kind", "factRevision", "occurredAt", "timezone" }) {
			var value = recorded();
			var fact = fact();
			fact.put(field, switch (field) {
				case "occurredAt" -> NOW.plusSeconds(1).toString();
				case "timezone" -> "not-a-zone";
				default -> "unexpected";
			});
			value.put("fact", fact);
			body = MAPPER.writeValueAsString(value);
			assertUnavailable(client(1, 5));
		}
		var deletedWithFact = recorded();
		deletedWithFact.put("state", "DELETED");
		body = MAPPER.writeValueAsString(deletedWithFact);
		assertUnavailable(client(1, 5));
	}

	@Test
	void openCircuitFailsFastWithoutAnotherOwnerRequest() {
		status = 503;
		var client = client(1, 2);
		assertUnavailable(client);
		assertUnavailable(client);
		assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
		assertUnavailable(client);
		assertThat(attempts.get()).isEqualTo(2);
	}

	@Test
	void slowOwnerHasABoundedCallerBudgetAndNeverReturnsFalseAbsence() {
		delayMillis = 1200;
		long started = System.nanoTime();
		assertUnavailable(client(2, 5));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
		assertThat(attempts.get()).isEqualTo(2);
	}

	@Test
	void rejectsUnsafeConfigurationBudgets() {
		assertThatThrownBy(() -> new EmotionCheckInEvidenceProperties("", Duration.ofSeconds(1),
				Duration.ofSeconds(1), 2, Duration.ofMillis(100), 10, 5, 50, Duration.ofSeconds(10), 2))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private EmotionCheckInEvidenceClient client(int maximumAttempts, int minimumCalls) {
		var properties = new EmotionCheckInEvidenceProperties("", Duration.ofMillis(100), Duration.ofMillis(200),
				maximumAttempts, Duration.ofMillis(1), 10, minimumCalls, 50, Duration.ofSeconds(10), 2);
		var configuration = new EmotionCheckInEvidenceFeignConfiguration();
		var http = Feign.builder().contract(new SpringMvcContract()).decoder(new JacksonDecoder(MAPPER))
				.options(configuration.emotionCheckInEvidenceRequestOptions(properties)).retryer(Retryer.NEVER_RETRY)
				.target(EmotionCheckInEvidenceHttpClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
		return new EmotionCheckInEvidenceClient(http, properties, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private void assertUnavailable(EmotionCheckInEvidenceClient client) {
		assertThatThrownBy(() -> client.read(USER, DATE, "token", CORRELATION))
				.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code())
						.isEqualTo("EMOTION_CHECK_IN_EVIDENCE_UNAVAILABLE"));
	}

	private Map<String, Object> recorded() {
		var value = new LinkedHashMap<String, Object>();
		value.put("schemaVersion", "emotion-check-in-completion-evidence-v1");
		value.put("subjectUserId", USER.toString());
		value.put("localDate", DATE.toString());
		value.put("state", "RECORDED");
		value.put("fact", fact());
		value.put("verifiedAt", NOW.toString());
		return value;
	}

	private Map<String, Object> fact() {
		var value = new LinkedHashMap<String, Object>();
		value.put("ownerDomain", "JOURNAL_AI");
		value.put("kind", "EMOTION_CHECK_IN");
		value.put("factId", FACT.toString());
		value.put("factRevision", "1");
		value.put("occurredAt", NOW.minusSeconds(10).toString());
		value.put("timezone", "Asia/Ho_Chi_Minh");
		return value;
	}
}
