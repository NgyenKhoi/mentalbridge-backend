package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

class ChatIdentityHttpConsumerTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> correlation = new AtomicReference<>();
    private final AtomicReference<String> bearer = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200), attempts = new AtomicInteger(), delay = new AtomicInteger();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/internal/v1/community-chat/account-authorizations", exchange -> {
            attempts.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            token.set(exchange.getRequestHeaders().getFirst("X-MentalBridge-Service-Token"));
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if (delay.get() > 0) Thread.sleep(delay.get());
                var body = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), body.length); exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    @Test void sendsOnlyTheDedicatedCredentialExactSubjectsAndCorrelation() throws Exception {
        var actor = UUID.randomUUID(); var peer = UUID.randomUUID(); var id = UUID.randomUUID();
        response.set(mapper.writeValueAsString(Map.of("activeUserAccountIds", List.of(actor, peer), "futureOptional", true)));
        client(1000).require(List.of(actor, peer, actor), id);
        var wire = mapper.readTree(requestBody.get());
        assertThat(wire.size()).isOne(); assertThat(wire.path("accountIds").size()).isEqualTo(2);
        assertThat(token.get()).isEqualTo("synthetic-community-service-token-at-least-32");
        assertThat(correlation.get()).isEqualTo(id.toString()); assertThat(bearer.get()).isNull();
        assertThat(attempts.get()).isOne();
    }
    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503})
    void ownerProblemsFailClosedWithoutRetryOrLeakingTheRawResponse(int code) {
        status.set(code); response.set("{\"code\":\"synthetic owner problem\"}");
        assertThatThrownBy(() -> client(1000).require(List.of(UUID.randomUUID()), UUID.randomUUID()))
                .isInstanceOfSatisfying(ChatException.class, exception -> assertThat(exception.status).isEqualTo(503))
                .hasMessageNotContaining("synthetic owner problem");
        assertThat(attempts.get()).isOne();
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"activeUserAccountIds\":null}", "{\"activeUserAccountIds\":[\"bad\"]}", "{\"activeUserAccountIds\":[", "[]"})
    void malformedResponsesCannotBecomeAnAllowedAccountState(String body) {
        response.set(body);
        assertThatThrownBy(() -> client(1000).require(List.of(UUID.randomUUID()), UUID.randomUUID()))
                .isInstanceOfSatisfying(ChatException.class, exception -> assertThat(exception.status).isEqualTo(503));
    }
    @Test void anEmptyCurrentResultDeniesEvenWithAPreviouslyValidActor() {
        response.set("{\"activeUserAccountIds\":[]}");
        assertThatThrownBy(() -> client(1000).require(List.of(UUID.randomUUID()), UUID.randomUUID()))
                .isInstanceOfSatisfying(ChatException.class, exception -> assertThat(exception.status).isEqualTo(404));
    }
    @Test void timeoutsAreBoundedAndDoNotRetryTheReadPost() {
        response.set("{\"activeUserAccountIds\":[]}"); delay.set(500);
        var started = System.nanoTime();
        assertThatThrownBy(() -> client(100).require(List.of(UUID.randomUUID()), UUID.randomUUID()))
                .isInstanceOfSatisfying(ChatException.class, exception -> assertThat(exception.status).isEqualTo(503));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(attempts.get()).isOne();
    }
    private ChatAccountAuthorizer client(int timeoutMs) {
        var properties = new ChatProperties(true, "", "synthetic-community-service-token-at-least-32", Duration.ofMillis(100),
                Duration.ofMillis(timeoutMs), "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "v1", "{}");
        var configuration = new ChatIdentityFeignConfiguration();
        var http = Feign.builder().contract(new SpringMvcContract()).encoder(new JacksonEncoder(mapper))
                .decoder(new JacksonDecoder(mapper)).options(configuration.chatIdentityDeadlines(properties))
                .retryer(configuration.chatIdentityRetryer()).target(ChatIdentityHttpClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
        return new ChatAccountAuthorizer(http, properties);
    }
}
