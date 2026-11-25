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
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.configuration.ResourceEvidenceProperties;
import com.mentalbridge.care.shared.ApiException;
import com.sun.net.httpserver.HttpServer;

import feign.Feign;
import feign.Request;
import feign.Retryer;
import feign.jackson.JacksonDecoder;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class ResourceEvidenceHttpConsumerContractTests {
	private static final UUID RESOURCE = UUID.randomUUID();
	private static final UUID CORRELATION = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	private HttpServer server;
	private ExecutorService executor;
	private String body;
	private int status;
	private long delay;
	private String authorization;
	private String correlation;
	private String query;
	private final AtomicInteger attempts = new AtomicInteger();

	@BeforeEach
	void provider() throws Exception {
		status = 200;
		body = mapper.writeValueAsString(new ResourceEvidenceHttpClient.Evidence("RESOURCE_COMPLETION",
				RESOURCE + ":2026-10-21", "7", NOW.minusSeconds(60), NOW));
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newCachedThreadPool();
		server.setExecutor(executor);
		server.createContext("/api/v1/resource-completion-evidence", exchange -> {
			attempts.incrementAndGet();
			authorization = exchange.getRequestHeaders().getFirst("Authorization");
			correlation = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
			query = exchange.getRequestURI().getQuery();
			try {
				if (delay > 0) Thread.sleep(delay);
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
			}
			catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
			finally { exchange.close(); }
		});
		server.start();
	}

	@AfterEach
	void close() { server.stop(0); executor.shutdownNow(); }

	@Test
	void forwardsOwnerBearerCorrelationExactVersionAndDateUsingCanonicalProviderContract() {
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var parsed = new OpenAPIV3Parser().readLocation(Path.of("..", "contracts", "openapi",
				"content-notification-service.yaml").toAbsolutePath().toUri().toString(), null, options);
		assertThat(parsed.getMessages()).isEmpty();
		var operation = parsed.getOpenAPI().getPaths().get("/api/v1/resource-completion-evidence").getGet();
		assertThat(operation.getResponses()).containsKeys("200", "401", "404", "503");
		assertThat(read(client()).resourceVersion()).isEqualTo("7");
		assertThat(authorization).isEqualTo("Bearer owner-token");
		assertThat(correlation).isEqualTo(CORRELATION.toString());
		assertThat(query).contains("resourceId=" + RESOURCE, "resourceVersion=7", "localDate=2026-10-21", "timeZone=Asia/Ho_Chi_Minh");
	}

	@Test
	void forwardsUsedFactIdsAsRepeatedQueryParameters() {
		String session = UUID.randomUUID().toString();
		String completion = RESOURCE + ":2026-10-21";
		client().read(RESOURCE, "7", LocalDate.parse("2026-10-21"), "Asia/Ho_Chi_Minh",
				List.of(session, completion), "owner-token", CORRELATION);
		assertThat(query).contains("excludedFactIds=" + session, "excludedFactIds=" + completion);
	}

	@Test
	void absenceIsNotCompletionAndDoesNotRetry() {
		status = 404;
		assertThat(read(client())).isNull();
		assertThat(attempts.get()).isEqualTo(1);
	}

	@Test
	void retriesTransientFailuresWithinBoundedBudgetAndReturnsUnavailable() {
		status = 503;
		assertThatThrownBy(() -> read(client())).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("RESOURCE_EVIDENCE_UNAVAILABLE"));
		assertThat(attempts.get()).isEqualTo(2);
	}

	@Test
	void malformedOrWrongVersionEvidenceFailsClosedWithoutRetry() {
		body = "{\"factKind\":\"RESOURCE_COMPLETION\",\"factId\":\"fact\",\"resourceVersion\":\"8\","
				+ "\"occurredAt\":\"2026-10-21T07:59:00Z\",\"verifiedAt\":\"2026-10-21T08:00:00Z\"}";
		assertThatThrownBy(() -> read(client())).isInstanceOf(ApiException.class);
		assertThat(attempts.get()).isEqualTo(1);
	}

	@Test
	void timeoutIsUnavailableAndRetriesOnlySafeRead() {
		delay = 400;
		assertThatThrownBy(() -> read(client())).isInstanceOf(ApiException.class);
		assertThat(attempts.get()).isEqualTo(2);
	}

	private ResourceEvidenceClient client() {
		var props = new ResourceEvidenceProperties("", Duration.ofMillis(100), Duration.ofMillis(100),
				2, Duration.ofMillis(5), 10, 5, 50, Duration.ofSeconds(10), 2);
		var http = Feign.builder().contract(new SpringMvcContract()).decoder(new JacksonDecoder(mapper))
				.options(new Request.Options(props.connectTimeout(), props.readTimeout(), false))
				.retryer(Retryer.NEVER_RETRY).target(ResourceEvidenceHttpClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
		return new ResourceEvidenceClient(http, props, Clock.fixed(NOW, ZoneOffset.UTC));
	}
	private ResourceEvidenceHttpClient.Evidence read(ResourceEvidenceClient client) {
		return client.read(RESOURCE, "7", LocalDate.parse("2026-10-21"), "Asia/Ho_Chi_Minh", List.of(), "owner-token", CORRELATION);
	}
}
