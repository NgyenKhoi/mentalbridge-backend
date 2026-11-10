package com.mentalbridge.care.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import feign.Feign;
import feign.FeignException;
import feign.jackson.JacksonDecoder;

class JournalActivityHttpConsumerContractTests {

	private HttpServer server;
	private final AtomicReference<String> body = new AtomicReference<>();

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/v1/emotion-check-ins", exchange -> {
			assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer owner-token");
			assertThat(exchange.getRequestHeaders().getFirst("X-Correlation-Id")).isEqualTo("synthetic-correlation");
			assertThat(exchange.getRequestURI().getQuery()).isEqualTo("limit=90");
			byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (var output = exchange.getResponseBody()) { output.write(bytes); }
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void decodesOwnerContractValidEmotionPageOverHttp() {
		body.set("""
				{"items":[{"id":"10000000-0000-4000-8000-000000000001",
				"localDate":"2026-11-10","timezone":"Asia/Bangkok","emotion":"GOOD",
				"intensity":4,"factors":[],"note":null,"sourceLabel":"SELF_REPORTED_EMOTION",
				"clinicalUse":"NOT_A_DIAGNOSIS_OR_SAFETY_CLASSIFIER","revision":1,
				"recordedAt":"2026-11-09T17:30:00Z","createdAt":"2026-11-09T17:30:00Z",
				"updatedAt":"2026-11-09T17:30:00Z"}],"page":{"limit":90,"hasMore":false},
				"label":"SELF_REPORTED_EMOTION","interpretation":"NOT_DIAGNOSIS_OR_RECOVERY"}
				""");

		var page = client().emotions("Bearer owner-token", "synthetic-correlation", 90);

		assertThat(page.items()).hasSize(1);
		assertThat(page.items().getFirst().emotion()).isEqualTo("GOOD");
		assertThat(page.items().getFirst().recordedAt()).isEqualTo(Instant.parse("2026-11-09T17:30:00Z"));
		assertThat(page.page().hasMore()).isFalse();
	}

	@Test
	void malformedWirePayloadRaisesFeignDecodeFailure() {
		body.set("{malformed");

		assertThatThrownBy(() -> client().emotions("Bearer owner-token", "synthetic-correlation", 90))
				.isInstanceOf(FeignException.class);
	}

	private JournalActivityHttpClient client() {
		return Feign.builder().contract(new SpringMvcContract())
				.decoder(new JacksonDecoder(new ObjectMapper().findAndRegisterModules()))
				.target(JournalActivityHttpClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
	}
}
