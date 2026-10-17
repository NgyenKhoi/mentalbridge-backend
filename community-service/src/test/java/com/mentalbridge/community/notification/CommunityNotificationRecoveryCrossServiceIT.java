package com.mentalbridge.community.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CommunityNotificationRecoveryCrossServiceIT {

	private static final String TOPIC = "mentalbridge.community.interaction.v1";
	private static final String DEAD_LETTER_TOPIC =
			"mentalbridge.content-notification.community-interaction-dead-letter.v1";
	private static final String CONSUMER_GROUP = "content-notification-community-interactions-v1";
	private static final UUID POST_OWNER = UUID.fromString("00000000-0000-4000-8000-000000000171");
	private static final UUID COMMENTER = UUID.fromString("00000000-0000-4000-8000-000000000172");
	private static final KeyPair JWT_KEY_PAIR = keyPair();

	@Container
	static final PostgreSQLContainer COMMUNITY_DATABASE = new PostgreSQLContainer(
			DockerImageName.parse("postgres:17-alpine"));

	@Container
	static final PostgreSQLContainer NOTIFICATION_DATABASE = new PostgreSQLContainer(
			DockerImageName.parse("postgres:17-alpine"));

	@Container
	static final KafkaContainer KAFKA = new KafkaContainer(
			DockerImageName.parse("apache/kafka-native:3.9.1"));

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcClient communityJdbc;
	@Autowired CommunityInteractionOutboxRelayPersistence relayPersistence;
	@Autowired CommunityInteractionRelayProperties relayProperties;
	@Autowired KafkaTemplate<String, String> kafkaTemplate;

	private Process notificationService;
	private Path notificationLog;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", COMMUNITY_DATABASE::getJdbcUrl);
		properties.add("spring.datasource.username", COMMUNITY_DATABASE::getUsername);
		properties.add("spring.datasource.password", COMMUNITY_DATABASE::getPassword);
		properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
		properties.add("debug", () -> "false");
		properties.add("logging.level.root", () -> "WARN");
		properties.add("logging.level.org.apache.kafka", () -> "WARN");
		properties.add("mentalbridge.community.jwt.issuer", () -> "https://identity.test.mentalbridge");
		properties.add("mentalbridge.community.jwt.audience", () -> "mentalbridge-test-api");
		properties.add("mentalbridge.community.jwt.public-key",
				() -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
		properties.add("mentalbridge.community.cloudinary.cloud-name", () -> "test-cloud");
		properties.add("mentalbridge.community.cloudinary.api-key", () -> "test-api-key");
		properties.add("mentalbridge.community.cloudinary.api-secret", () -> "test-api-secret");
		properties.add("mentalbridge.community.cloudinary.timeout-seconds", () -> "5");
		properties.add("mentalbridge.community.media.max-image-bytes", () -> "10485760");
		properties.add("mentalbridge.community.media.max-video-bytes", () -> "52428800");
		properties.add("mentalbridge.community.media.max-video-duration-seconds", () -> "60");
		properties.add("mentalbridge.community.media.upload-intent-ttl", () -> "10m");
		properties.add("mentalbridge.community.media.orphan-retention", () -> "24h");
		properties.add("mentalbridge.community.media.cleanup-interval", () -> "1h");
		properties.add("mentalbridge.community.interaction-relay.enabled", () -> "false");
		properties.add("mentalbridge.community.interaction-relay.topic", () -> TOPIC);
		properties.add("mentalbridge.community.interaction-relay.batch-size", () -> "10");
		properties.add("mentalbridge.community.interaction-relay.interval", () -> "1h");
		properties.add("mentalbridge.community.interaction-relay.send-timeout", () -> "5s");
		properties.add("mentalbridge.community.interaction-relay.retry-base", () -> "1s");
		properties.add("mentalbridge.community.interaction-relay.retry-maximum", () -> "5s");
	}

	@BeforeAll
	static void prepareBoundaries() throws Exception {
		try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers()))) {
			admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1),
					new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
		}
		runNotificationMigrations();
	}

	@AfterEach
	void stopNotificationService() throws Exception {
		stopNotificationProcess();
	}

	@Test
	void communityCommitsDuringNotificationOutageAndConsumerCatchesUpExactlyOnceAfterRecovery()
			throws Exception {
		var postId = createPost();
		createComment(postId);

		assertThat(notificationService).isNull();
		assertThat(communityJdbc.sql("select count(*) from community_comment").query(Long.class).single())
				.isEqualTo(1L);
		var pending = outboxRecord();
		assertThat(pending.published()).isFalse();
		assertThat(pending.attemptCount()).isZero();

		var sink = new KafkaCommunityInteractionEventSink(relayProperties, kafkaTemplate, objectMapper);
		new CommunityInteractionOutboxRelay(relayPersistence, sink).publishDue();

		var published = outboxRecord();
		assertThat(published.eventId()).isEqualTo(pending.eventId());
		assertThat(published.published()).isTrue();
		assertThat(published.attemptCount()).isEqualTo(1);
		assertThat(published.payload()).doesNotContain("Private post body", "Private comment body");
		assertThat(objectMapper.readTree(readFactFromKafka(published.eventId())))
				.isEqualTo(objectMapper.readTree(published.payload()));

		startNotificationProcess();
		awaitConsumerOffset("the recovered consumer to commit the first Kafka offset", 1,
				Duration.ofSeconds(30));
		await("one Community notification to materialize", () -> notificationCount() == 1,
				Duration.ofSeconds(30));
		assertNotification(published.eventId(), postId);

		stopNotificationProcess();
		startNotificationProcess();
		assertThat(notificationCount()).isEqualTo(1);

		sink.publish(published.targetId(), objectMapper.readTree(published.payload()));
		awaitConsumerOffset("the restarted consumer to commit the replay offset", 2,
				Duration.ofSeconds(30));
		assertThat(notificationCount()).isEqualTo(1);
		assertNotification(published.eventId(), postId);
	}

	private UUID createPost() throws Exception {
		var response = mvc.perform(post("/api/v1/community/posts").with(user(POST_OWNER))
				.header("Idempotency-Key", "recovery-post-create-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "Private post body",
						"topics", List.of("MY_STORY"), "mediaIds", List.of()))))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("postId").asText());
	}

	private void createComment(UUID postId) throws Exception {
		var body = objectMapper.createObjectNode().put("content", "Private comment body");
		body.putNull("parentCommentId");
		mvc.perform(post("/api/v1/community/posts/{postId}/comments", postId).with(user(COMMENTER))
				.header("Idempotency-Key", "recovery-comment-create-0001")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
				.andExpect(status().isCreated());
	}

	private OutboxRecord outboxRecord() {
		return communityJdbc.sql("""
				select id, target_id, event_payload::text, published_at is not null, attempt_count
				from community_interaction_outbox
				""").query((result, row) -> new OutboxRecord(result.getObject(1, UUID.class),
				result.getObject(2, UUID.class), result.getString(3), result.getBoolean(4),
				result.getInt(5))).single();
	}

	private String readFactFromKafka(UUID eventId) {
		var properties = new Properties();
		properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		properties.put(ConsumerConfig.GROUP_ID_CONFIG, "mb617-inspection-" + UUID.randomUUID());
		properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		try (var consumer = new KafkaConsumer<String, String>(properties)) {
			consumer.subscribe(List.of(TOPIC));
			var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
			while (System.nanoTime() < deadline) {
				for (var record : consumer.poll(Duration.ofMillis(250))) {
					try {
						if (eventId.toString().equals(objectMapper.readTree(record.value()).path("eventId").asText()))
							return record.value();
					}
					catch (IOException exception) {
						throw new IllegalStateException(exception);
					}
				}
			}
		}
		throw new AssertionError("Community interaction fact was not retained in Kafka");
	}

	private void startNotificationProcess() throws Exception {
		var port = availablePort();
		notificationLog = Files.createTempFile("mb617-content-notification-", ".log");
		notificationLog.toFile().deleteOnExit();
		var builder = new ProcessBuilder("node", "dist/main.js")
				.directory(contentNotificationDirectory().toFile()).redirectErrorStream(true)
				.redirectOutput(notificationLog.toFile());
		var environment = builder.environment();
		environment.put("NODE_ENV", "test");
		environment.put("PORT", Integer.toString(port));
		environment.put("DATABASE_URL", notificationDatabaseUrl());
		environment.put("LOG_LEVEL", "silent");
		environment.put("CORS_ORIGINS", "");
		environment.put("IDENTITY_JWT_ISSUER", "https://identity.test.mentalbridge");
		environment.put("IDENTITY_JWT_AUDIENCE", "mentalbridge-test-api");
		environment.put("IDENTITY_JWT_PUBLIC_KEY", publicKeyPem());
		environment.put("KAFKA_BOOTSTRAP_SERVERS", nodeKafkaBootstrapServers());
		environment.put("CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED", "false");
		environment.put("CONTENT_APPOINTMENT_CONSUMER_ENABLED", "false");
		environment.put("CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED", "true");
		environment.put("REMINDER_SCHEDULER_ENABLED", "false");
		environment.put("WELLBEING_DIGEST_SCHEDULER_ENABLED", "false");
		notificationService = builder.start();
		await("Content/Notification health after recovery", () -> notificationHealthy(port),
				Duration.ofSeconds(30));
	}

	private void stopNotificationProcess() throws Exception {
		if (notificationService == null) return;
		notificationService.destroy();
		if (!notificationService.waitFor(10, TimeUnit.SECONDS)) {
			notificationService.destroyForcibly();
			notificationService.waitFor(10, TimeUnit.SECONDS);
		}
		notificationService = null;
	}

	private boolean notificationHealthy(int port) {
		if (notificationService == null || !notificationService.isAlive()) {
			throw new AssertionError("Content/Notification stopped during recovery:\n" + notificationLog());
		}
		try {
			var connection = (HttpURLConnection) URI
					.create("http://127.0.0.1:" + port + "/health/live").toURL().openConnection();
			connection.setConnectTimeout(500);
			connection.setReadTimeout(500);
			return connection.getResponseCode() == 200;
		}
		catch (IOException exception) {
			return false;
		}
	}

	private long notificationCount() {
		try (var connection = DriverManager.getConnection(NOTIFICATION_DATABASE.getJdbcUrl(),
				NOTIFICATION_DATABASE.getUsername(), NOTIFICATION_DATABASE.getPassword());
				var statement = connection.prepareStatement(
						"select count(*) from notification where source = 'COMMUNITY_INTERACTION_V1'")) {
			try (var result = statement.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private void assertNotification(UUID eventId, UUID postId) throws Exception {
		try (var connection = DriverManager.getConnection(NOTIFICATION_DATABASE.getJdbcUrl(),
				NOTIFICATION_DATABASE.getUsername(), NOTIFICATION_DATABASE.getPassword());
				var statement = connection.prepareStatement("""
						select recipient_id, category, action_type, action_target_id, source_identity,
						       delivery_state
						from notification
						where source = 'COMMUNITY_INTERACTION_V1'
						""")) {
			try (var result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				assertThat(result.getObject("recipient_id", UUID.class)).isEqualTo(POST_OWNER);
				assertThat(result.getString("category")).isEqualTo("COMMUNITY_COMMENT");
				assertThat(result.getString("action_type")).isEqualTo("OPEN_COMMUNITY_POST");
				assertThat(result.getObject("action_target_id", UUID.class)).isEqualTo(postId);
				assertThat(result.getString("source_identity")).isEqualTo(eventId.toString());
				assertThat(result.getString("delivery_state")).isEqualTo("DELIVERED");
				assertThat(result.next()).isFalse();
			}
		}
	}

	private void awaitConsumerOffset(String description, long expected, Duration timeout)
			throws Exception {
		try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers()))) {
			var deadline = System.nanoTime() + timeout.toNanos();
			while (System.nanoTime() < deadline) {
				var offsets = admin.listConsumerGroupOffsets(CONSUMER_GROUP)
						.partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
				var offset = offsets.get(new TopicPartition(TOPIC, 0));
				if (offset != null && offset.offset() >= expected) return;
				Thread.sleep(250);
			}
		}
		throw new AssertionError("Timed out waiting for " + description);
	}

	private static void runNotificationMigrations() throws Exception {
		var log = Files.createTempFile("mb617-content-migration-", ".log");
		try {
			var builder = new ProcessBuilder("node",
					"node_modules/node-pg-migrate/bin/node-pg-migrate.js", "up", "--migrations-dir",
					"migrations").directory(contentNotificationDirectory().toFile())
					.redirectErrorStream(true).redirectOutput(log.toFile());
			builder.environment().put("DATABASE_URL", notificationDatabaseUrl());
			var process = builder.start();
			assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue();
			assertThat(process.exitValue()).as(readLog(log)).isZero();
		}
		finally {
			Files.deleteIfExists(log);
		}
	}

	private static String notificationDatabaseUrl() {
		return "postgresql://" + NOTIFICATION_DATABASE.getUsername() + ":"
				+ NOTIFICATION_DATABASE.getPassword() + "@" + NOTIFICATION_DATABASE.getHost() + ":"
				+ NOTIFICATION_DATABASE.getMappedPort(5432) + "/" + NOTIFICATION_DATABASE.getDatabaseName();
	}

	private static String nodeKafkaBootstrapServers() {
		return KAFKA.getBootstrapServers().replace("PLAINTEXT://", "");
	}

	private static Path contentNotificationDirectory() {
		var sibling = Path.of("..", "content-notification-service").toAbsolutePath().normalize();
		if (Files.isDirectory(sibling)) return sibling;
		var child = Path.of("content-notification-service").toAbsolutePath().normalize();
		if (Files.isDirectory(child)) return child;
		throw new IllegalStateException("content-notification-service directory is unavailable");
	}

	private static int availablePort() throws IOException {
		try (var socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static String publicKeyPem() {
		var encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
				.encodeToString(JWT_KEY_PAIR.getPublic().getEncoded());
		return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
	}

	private String notificationLog() {
		return notificationLog == null ? "" : readLog(notificationLog);
	}

	private static String readLog(Path path) {
		try {
			var content = Files.readString(path, StandardCharsets.UTF_8);
			return content.substring(Math.max(0, content.length() - 4000));
		}
		catch (IOException exception) {
			return "log unavailable";
		}
	}

	private static void await(String description, BooleanSupplier condition, Duration timeout)
			throws InterruptedException {
		var deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) return;
			Thread.sleep(100);
		}
		throw new AssertionError("Timed out waiting for " + description);
	}

	private static KeyPair keyPair() {
		try {
			var generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID subject) {
		return jwt().jwt(token -> token.subject(subject.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private record OutboxRecord(UUID eventId, UUID targetId, String payload, boolean published,
			int attemptCount) {
	}
}
