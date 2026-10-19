package com.mentalbridge.care.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
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
class UserNotificationOutboxKafkaIntegrationTests extends CareTestProperties {

	private static final String TOPIC = "mentalbridge.care.user-notification.v1";

	@Container
	static final KafkaContainer KAFKA = new KafkaContainer(
			DockerImageName.parse("apache/kafka-native:3.9.1"));

	@Autowired UserNotificationOutbox outbox;
	@Autowired UserNotificationOutboxRelayPersistence persistence;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;

	@BeforeAll
	static void createTopic() throws Exception {
		try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers()))) {
			admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all()
					.get(30, TimeUnit.SECONDS);
		}
	}

	@Test
	void publishesTheCommittedFactToKafkaOnceAndMarksItOnlyAfterBrokerAcknowledgement()
			throws Exception {
		var targetId = UUID.fromString("70000000-0000-4000-8000-000000000001");
		var recipientId = UUID.fromString("70000000-0000-4000-8000-000000000002");
		var correlationId = UUID.fromString("70000000-0000-4000-8000-000000000003");
		var availableAt = Instant.parse("2026-10-19T08:00:00Z");
		outbox.append(recipientId, FactType.ASSESSMENT_RESULT_AVAILABLE, targetId, 0,
				correlationId, availableAt);

		var producerConfiguration = new HashMap<String, Object>();
		producerConfiguration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		producerConfiguration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		producerConfiguration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		producerConfiguration.put(ProducerConfig.ACKS_CONFIG, "all");
		producerConfiguration.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		var producerFactory = new DefaultKafkaProducerFactory<String, String>(producerConfiguration);
		try {
			var relay = new UserNotificationOutboxRelay(persistence, properties(),
					new KafkaTemplate<>(producerFactory), json);
			relay.publishDue();
			relay.publishDue();

			var messages = readMessages();
			assertThat(messages).hasSize(1);
			var envelope = json.readTree(messages.getFirst());
			assertThat(envelope.get("aggregateId").asText()).isEqualTo(targetId.toString());
			assertThat(envelope.at("/payload/recipientId").asText()).isEqualTo(recipientId.toString());
			assertThat(envelope.at("/payload/factType").asText())
					.isEqualTo("ASSESSMENT_RESULT_AVAILABLE");
			assertThat(envelope.toString()).doesNotContain("answer", "score", "safetyStatus");
			assertThat(jdbc.sql("""
					select published_at is not null from outbox_event
					where aggregate_id = :targetId and message_type = 'care.user-notification.fact'
					""").param("targetId", targetId).query(Boolean.class).single()).isTrue();
		}
		finally {
			producerFactory.destroy();
		}
	}

	private List<String> readMessages() {
		var configuration = new Properties();
		configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		configuration.put(ConsumerConfig.GROUP_ID_CONFIG, "mb565-care-inspection-" + UUID.randomUUID());
		configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		try (var consumer = new KafkaConsumer<String, String>(configuration)) {
			consumer.subscribe(List.of(TOPIC));
			var records = new java.util.ArrayList<String>();
			var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
			while (System.nanoTime() < deadline) {
				consumer.poll(Duration.ofMillis(250)).forEach(record -> records.add(record.value()));
				if (!records.isEmpty()) break;
			}
			return records;
		}
	}

	private static UserNotificationRelayProperties properties() {
		return new UserNotificationRelayProperties(true, TOPIC, 100, Duration.ofSeconds(5),
				Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(5));
	}
}
