package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mentalbridge.community.chat.enabled=true",
        "mentalbridge.community.chat.identity-service-token=synthetic-community-chat-token-at-least-32",
        "mentalbridge.community.chat.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "mentalbridge.community.chat.encryption-key-version=test-v1",
        "mentalbridge.community.chat.identity-base-url=http://127.0.0.1:1",
        "debug=false", "logging.level.root=WARN", "logging.level.org.apache.kafka=ERROR",
        "logging.level.org.springframework=WARN", "logging.level.org.hibernate.SQL=OFF"
})
class CommunityChatNotificationCrossServiceIT extends ChatGroupIntegrationSupport {
    private static final KeyPair CONTENT_KEYS = CommunityChatRealtimeIntegrationTests.keys();
    private static final String CONSUMER = "content-notification-community-notices-v1";
    private static final String CANARY = "MB693_SYNTHETIC_PRIVATE_BODY";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    @Container static final PostgreSQLContainer CONTENT_DB = new PostgreSQLContainer("postgres:17-alpine");
    @Container static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));

    @LocalServerPort int communityPort;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ChatRelayPersistence relayPersistence;
    @Autowired ChatProperties chatProperties;
    @MockitoSpyBean(name = "chatTestClock") TestClock wallClock;
    private Process contentProcess;
    private CompletableFuture<String> contentOutput;
    private int contentPort;

    @DynamicPropertySource static void integrationProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("mentalbridge.community.chat-notification.enabled", () -> true);
        properties.add("mentalbridge.community.chat-notification.key-id", () -> "mb693-content");
        properties.add("mentalbridge.community.chat-notification.public-key", () -> pem("PUBLIC KEY", CONTENT_KEYS.getPublic().getEncoded()));
    }

    @BeforeAll static void prepareContentAndKafka() throws Exception {
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(ChatRelayProperties.TOPIC, "mentalbridge.community.comment-reaction.v1",
                    "mentalbridge.content-notification.community-interaction-dead-letter.v1").stream()
                    .map(name -> new NewTopic(name, 1, (short) 1)).toList()).all().get(30, TimeUnit.SECONDS);
        }
        var migrationLog = Files.createTempFile("mb693-content-migrations-", ".log");
        try {
            var builder = new ProcessBuilder(node(), "node_modules/node-pg-migrate/bin/node-pg-migrate.js", "up", "--migrations-dir", "migrations")
                    .directory(contentDirectory().toFile()).redirectErrorStream(true).redirectOutput(migrationLog.toFile());
            builder.environment().put("DATABASE_URL", contentDatabaseUrl());
            var process = builder.start();
            try {
                assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("Content migrations finished").isTrue();
                assertThat(process.exitValue()).as("Content migrations: %s", Files.readString(migrationLog)).isZero();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } finally {
            Files.deleteIfExists(migrationLog);
        }
    }

    @BeforeEach void isolateContentAndUseWallTime() throws Exception {
        doAnswer(call -> Instant.now()).when(wallClock).instant();
        execute("TRUNCATE community_notification_job,notification,notification_preference CASCADE");
    }

    @AfterEach void stopContent() throws Exception {
        if (contentProcess != null) {
            contentProcess.destroy();
            if (!contentProcess.waitFor(10, TimeUnit.SECONDS)) {
                contentProcess.destroyForcibly();
                assertThat(contentProcess.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
        if (contentOutput != null) {
            assertThat(contentOutput.get(5, TimeUnit.SECONDS))
                    .contains("request completed", "[REDACTED]")
                    .doesNotContain(CANARY, "Bearer ", "PRIVATE KEY", "recipientRoutingReference");
        }
    }

    @Test void quietHoursRecheckBlockBeforePublishingANewInboxNotice() throws Exception {
        preference(b, "ANYONE", "0").andExpect(status().isOk());
        var conversation = UUID.fromString(json(start(a, bp, UUID.randomUUID()).andExpect(status().isCreated())).path("conversationId").asText());
        var message = save(conversation);
        enableQuietHours();
        publishDue();
        startContent();
        await("quiet-hour job deferred after actual owner authorization", () -> scalar("SELECT count(*) FROM community_notification_job WHERE recipient_id=? AND source_identity=? AND state='PENDING' AND attempts>0 AND claim_token IS NULL AND available_at>now()+interval '30 seconds'", b, identity(message)) == 1);
        assertThat(inbox(b).path("items").size()).isZero();
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        releaseQuietHours();
        awaitState(message, "CANCELLED");
        assertThat(inbox(b).path("items").size()).isZero();
        assertThat(inbox(a).path("items").size()).isZero();
    }

    @Test void oldMembershipCannotDeliverAfterRejoinAndRecoveryReplaysOnlyOneNewNotice() throws Exception {
        var group = group();
        join(b, group, bp);
        var oldMessage = save(group);
        enableQuietHours();
        publishDue();
        startContent();
        await("old epoch notice deferred", () -> scalar("SELECT count(*) FROM community_notification_job WHERE recipient_id=? AND source_identity=? AND state='PENDING' AND attempts>0 AND claim_token IS NULL AND available_at>now()+interval '30 seconds'", b, identity(oldMessage)) == 1);
        remove(a, group, bp).andExpect(status().isNoContent());
        join(b, group, bp);
        releaseQuietHours();
        awaitState(oldMessage, "CANCELLED");
        assertThat(inbox(b).path("items").size()).isZero();

        var currentMessage = save(group);
        doThrow(new IllegalStateException("synthetic Identity outage")).when(identity).authorize(anyString(), anyString(), any());
        publishDue();
        await("503 retains a retryable job", () -> scalar("SELECT count(*) FROM community_notification_job WHERE recipient_id=? AND source_identity=? AND state='PENDING' AND attempts>0 AND claim_token IS NULL", b, identity(currentMessage)) == 1);
        assertThat(inbox(b).path("items").size()).isZero();
        doAnswer(call -> new ChatIdentityHttpClient.Result(call.getArgument(2, ChatIdentityHttpClient.Query.class).accountIds()))
                .when(identity).authorize(anyString(), anyString(), any());
        execute("UPDATE community_notification_job SET available_at=now() WHERE state='PENDING' AND claim_token IS NULL");
        awaitState(currentMessage, "DELIVERED");
        var page = inbox(b);
        assertThat(page.path("items").size()).isEqualTo(1);
        var notice = page.path("items").get(0);
        assertThat(notice.path("kind").asText()).isEqualTo("COMMUNITY_MESSAGE");
        assertThat(notice.path("body").asText()).isEqualTo("Bạn có tin nhắn mới.");
        assertThat(notice.path("action").path("type").asText()).isEqualTo("OPEN_COMMUNITY_CONVERSATION");
        assertThat(notice.path("action").path("targetId").asText()).isEqualTo(group.toString());
        assertThat(page.toString()).doesNotContain(CANARY, a.toString(), b.toString(), ap.toString(), bp.toString(),
                "Nhóm riêng kiểm thử", "Tên cộng đồng mẫu", "recipientRoutingReference", "aggregateVersion");
        assertThat(inbox(outsider).path("items").size()).isZero();
        assertThat(inbox(a).path("items").size()).isZero();

        var fact = mapper.readTree(jdbc.sql("SELECT event_payload::text FROM community_chat_outbox WHERE event_payload->'payload'->>'kind'='MESSAGE_RECEIVED' AND event_payload->'payload'->>'targetId'=:id")
                .param("id", currentMessage.toString()).query(String.class).single());
        var duplicate = fact.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) duplicate).put("messageId", UUID.randomUUID().toString());
        var offset = kafka.send(ChatRelayProperties.TOPIC, group.toString(), duplicate.toString()).get(10, TimeUnit.SECONDS).getRecordMetadata().offset();
        awaitConsumed(offset + 1);
        var replayed = inbox(b);
        assertThat(replayed.path("items").size()).isEqualTo(1);
        assertThat(replayed.path("items").get(0).path("id")).isEqualTo(notice.path("id"));
    }

    @Test void resolvedRequestsAndInvitationsCannotBecomeStaleNoticesOnConsumerRecovery() throws Exception {
        var request = pending(a, bp);
        var group = group();
        var invitation = invite(a, group, bp);
        publishDue();
        decision(b, request, "DECLINE", "0").andExpect(status().isNoContent());
        decide(b, invitation, "ACCEPT", 0).andExpect(status().isOk());
        startContent();
        awaitState("DIRECT_REQUESTED:" + request, "CANCELLED");
        awaitState("GROUP_INVITED:" + invitation, "CANCELLED");
        assertThat(inbox(b).path("items").size()).isZero();
    }

    private UUID save(UUID conversation) throws Exception {
        var result = json(mvc.perform(post(ROOT + "/conversations/{id}/messages", conversation).with(user(a))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(), "content", CANARY))))
                .andExpect(status().isCreated()));
        return UUID.fromString(result.path("message").path("messageId").asText());
    }

    private void publishDue() {
        var facts = jdbc.sql("SELECT event_payload::text FROM community_chat_outbox").query(String.class).list();
        assertThat(facts).allSatisfy(fact -> {
            assertThat(ChatWireContracts.validFact(fact)).isTrue();
            assertThat(fact).doesNotContain(CANARY, "email", "assessment");
        });
        var sink = new ChatKafkaSink(kafka, chatProperties);
        try {
            new ChatOutboxRelay(relayPersistence, sink, new ChatRelayProperties(false, 100, Duration.ofSeconds(5))).publishDue();
        } finally {
            sink.close();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM community_chat_outbox WHERE published_at IS NULL").query(Long.class).single()).isZero();
    }

    private void enableQuietHours() throws Exception {
        execute("INSERT INTO notification_preference(user_id,quiet_hours_enabled,quiet_hours_start,quiet_hours_end,time_zone) VALUES(?,true,(clock_timestamp() AT TIME ZONE 'UTC' - interval '1 minute')::time,(clock_timestamp() AT TIME ZONE 'UTC' + interval '10 minutes')::time,'UTC')", b);
    }

    private void releaseQuietHours() throws Exception {
        execute("UPDATE notification_preference SET quiet_hours_enabled=false WHERE user_id=?", b);
        execute("UPDATE community_notification_job SET available_at=now() WHERE state='PENDING' AND claim_token IS NULL");
    }

    private void awaitState(UUID message, String state) throws Exception { awaitState(identity(message), state); }
    private static String identity(UUID message) { return "MESSAGE_RECEIVED:" + message; }
    private void awaitState(String sourceIdentity, String state) throws Exception {
        await("durable notice state " + state, () -> scalar("SELECT count(*) FROM community_notification_job WHERE recipient_id=? AND source_identity=? AND state=?", b, sourceIdentity, state) == 1);
    }

    private JsonNode inbox(UUID actor) throws Exception {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("mb693-synthetic-identity").audience("mb693-api").subject(actor.toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60))).claim("roles", List.of("USER")).build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        token.sign(new RSASSASigner((RSAPrivateKey) CONTENT_KEYS.getPrivate()));
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + contentPort + "/api/v1/notifications"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token.serialize()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("private, no-store");
        return mapper.readTree(response.body());
    }

    private void startContent() throws Exception {
        try (var socket = new ServerSocket(0)) { contentPort = socket.getLocalPort(); }
        var builder = new ProcessBuilder(node(), "dist/main.js").directory(contentDirectory().toFile())
                .redirectErrorStream(true);
        var env = builder.environment();
        env.put("NODE_ENV", "test");
        env.put("PORT", Integer.toString(contentPort));
        env.put("DATABASE_URL", contentDatabaseUrl());
        env.put("LOG_LEVEL", "info");
        env.put("IDENTITY_JWT_ISSUER", "mb693-synthetic-identity");
        env.put("IDENTITY_JWT_AUDIENCE", "mb693-api");
        env.put("IDENTITY_JWT_PUBLIC_KEY", pem("PUBLIC KEY", CONTENT_KEYS.getPublic().getEncoded()));
        env.put("CONTENT_COMMUNITY_NOTIFICATION_PRIVATE_KEY", pem("PRIVATE KEY", CONTENT_KEYS.getPrivate().getEncoded()));
        env.put("CONTENT_COMMUNITY_NOTIFICATION_KEY_ID", "mb693-content");
        env.put("COMMUNITY_SERVICE_URL", "http://127.0.0.1:" + communityPort);
        env.put("KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers().replace("PLAINTEXT://", ""));
        env.put("CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED", "true");
        env.put("CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED", "false");
        env.put("CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED", "false");
        env.put("CONTENT_APPOINTMENT_CONSUMER_ENABLED", "false");
        env.put("CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED", "false");
        env.put("APPOINTMENT_REMINDER_ENABLED", "false");
        env.put("REMINDER_SCHEDULER_ENABLED", "false");
        env.put("WELLBEING_DIGEST_SCHEDULER_ENABLED", "false");
        contentProcess = builder.start();
        contentOutput = CompletableFuture.supplyAsync(() -> {
            try (var output = contentProcess.getInputStream()) {
                return new String(output.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) { throw new CompletionException(exception); }
        });
        await("real Content process health", () -> {
            try {
                if (!contentProcess.isAlive()) throw new AssertionError("Content process exited before readiness");
                return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + contentPort + "/health/live"))
                        .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
            } catch (IOException exception) { return false; }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
        });
    }

    private void awaitConsumed(long offset) throws Exception {
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            await("duplicate fact offset committed", () -> {
                try {
                    var offsets = admin.listConsumerGroupOffsets(CONSUMER).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                    var position = offsets.get(new TopicPartition(ChatRelayProperties.TOPIC, 0));
                    return position != null && position.offset() >= offset;
                } catch (Exception exception) { throw new IllegalStateException(exception); }
            });
        }
    }

    private void await(String description, BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out: " + description);
    }

    private static void execute(String sql, Object... parameters) throws Exception {
        try (var connection = DriverManager.getConnection(CONTENT_DB.getJdbcUrl(), CONTENT_DB.getUsername(), CONTENT_DB.getPassword());
                var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            statement.executeUpdate();
        }
    }

    private static long scalar(String sql, Object... parameters) {
        try (var connection = DriverManager.getConnection(CONTENT_DB.getJdbcUrl(), CONTENT_DB.getUsername(), CONTENT_DB.getPassword());
                var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            try (var result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static String contentDatabaseUrl() {
        return "postgresql://" + CONTENT_DB.getUsername() + ":" + CONTENT_DB.getPassword() + "@" + CONTENT_DB.getHost() + ":" + CONTENT_DB.getMappedPort(5432) + "/" + CONTENT_DB.getDatabaseName();
    }
    private static String pem(String kind, byte[] value) {
        return "-----BEGIN " + kind + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(value) + "\n-----END " + kind + "-----";
    }
    private static String node() { return System.getProperty("mb693.node", "node"); }
    private static Path contentDirectory() {
        var directory = Path.of("..", "content-notification-service").toAbsolutePath().normalize();
        if (!Files.isRegularFile(directory.resolve("dist/main.js"))) throw new IllegalStateException("Build sibling content-notification-service before running MB-693 cross-service IT");
        return directory;
    }
}
