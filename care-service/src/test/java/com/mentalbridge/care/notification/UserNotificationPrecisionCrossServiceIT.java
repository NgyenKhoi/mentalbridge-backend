package com.mentalbridge.care.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;
import com.mentalbridge.care.notification.UserNotificationOutbox.FactType;

@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserNotificationPrecisionCrossServiceIT extends CareTestProperties {

	private static final String TOPIC = "mentalbridge.care.user-notification.v1";

	@Container
	static final KafkaContainer KAFKA = new KafkaContainer(
			DockerImageName.parse("apache/kafka-native:3.9.1"));

	@Autowired UserNotificationOutbox outbox;
	@Autowired UserNotificationOutboxRelayPersistence persistence;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;

	@Test
	void nanosecondAvailabilitySurvivesPostgresRelayKafkaAndContentForBothCareFacts() throws Exception {
		var correlationId = UUID.randomUUID();
		try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers()))) {
			admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
		}
		for (var factType : FactType.values()) {
			for (var time : List.of("2026-10-19T08:00:00.123456789Z",
					"2026-10-19T08:00:00.123456999Z", "2026-10-19T08:00:00.999999999Z")) {
				var targetId = UUID.randomUUID();
				var original = Instant.parse(time);
				outbox.append(UUID.randomUUID(), factType, targetId, 0, correlationId, original);
				var persisted = jdbc.sql("select occurred_at from outbox_event where aggregate_id = :id")
						.param("id", targetId).query((row, index) -> row.getTimestamp(1).toInstant()).single();
				assertThat(persisted).isEqualTo(original.truncatedTo(ChronoUnit.MICROS));
			}
		}
		var producerConfiguration = new HashMap<String, Object>();
		producerConfiguration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		producerConfiguration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		producerConfiguration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		producerConfiguration.put(ProducerConfig.ACKS_CONFIG, "all");
		producerConfiguration.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		var factory = new DefaultKafkaProducerFactory<String, String>(producerConfiguration);
		try {
			var properties = new UserNotificationRelayProperties(true, TOPIC, 100, Duration.ofSeconds(5),
					Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(5));
			var relay = new UserNotificationOutboxRelay(persistence, properties, new KafkaTemplate<>(factory), json);
			relay.publishDue();
			assertThat(jdbc.sql("select count(*) from outbox_event where published_at is not null and correlation_id = :id")
					.param("id", correlationId).query(Long.class).single()).isEqualTo(6);
			verifyContent(KAFKA.getBootstrapServers(), "care", 6);
		}
		finally {
			factory.destroy();
		}
	}

	private void verifyContent(String broker, String owner, int expected) throws Exception {
		var directory = Path.of("..", "content-notification-service").toAbsolutePath().normalize();
		var log = Files.createTempFile("mb565-precision-", ".log");
		var process = new ProcessBuilder("node", "scripts/verify-user-notification-precision.mjs",
				owner, broker, Integer.toString(expected)).directory(directory.toFile())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("Content verification timeout").isTrue();
			assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
		}
		finally {
			if (process.isAlive()) process.destroyForcibly();
			Files.deleteIfExists(log);
		}
	}

}
