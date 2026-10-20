package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ChatKafkaRelayIntegrationTests extends ChatIntegrationSupport {
    @Container static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));
    @Autowired ChatKafkaSink sink;
    @Autowired ChatRelayPersistence persistence;
    @MockitoBean ChatOutboxRelay scheduledRelay;

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("mentalbridge.community.chat-relay.enabled", () -> "true");
        properties.add("logging.level.org.apache.kafka", () -> "WARN");
    }

    @BeforeAll
    static void topic() throws Exception {
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(ChatRelayProperties.TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void publishesActualCommittedContractWithConversationKeyAndNoContent() throws Exception {
        pending(a, bp);
        var fact = persistence.claim().orElseThrow();
        assertThat(persistence.claim()).isEmpty();
        assertThat(published()).isZero();
        sink.publish(fact);
        assertThat(published()).isZero();
        persistence.published(fact);
        assertThat(published()).isOne();
        var records = received(fact.id(), 1);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo(fact.conversationId().toString());
        assertThat(ChatWireContracts.validFact(records.getFirst().value())).isTrue();
        assertThat(mapper.readTree(records.getFirst().value())).isEqualTo(fact.payload());
    }

    @Test
    void leaseRecoveryBeforeSendAndAfterAckReplaysStableIdentityAndRejectsStaleWorker() throws Exception {
        pending(a, bp);
        var first = persistence.claim().orElseThrow();
        clock.advance(Duration.ofSeconds(11));
        var recovered = persistence.claim().orElseThrow();
        assertThat(recovered.id()).isEqualTo(first.id());
        persistence.published(first);
        assertThat(published()).isZero();
        sink.publish(recovered);
        clock.advance(Duration.ofSeconds(11));
        var afterAckCrash = persistence.claim().orElseThrow();
        assertThat(afterAckCrash.id()).isEqualTo(first.id());
        assertThat(afterAckCrash.payload()).isEqualTo(first.payload());
        sink.publish(afterAckCrash);
        persistence.published(afterAckCrash);
        persistence.failed(recovered);
        assertThat(published()).isOne();
        assertThat(persistence.claim()).isEmpty();
        assertThat(received(first.id(), 2)).hasSize(2);
        assertThat(count("community_chat_request")).isOne();
    }

    @Test
    void brokerOutageLeavesOwnerCommitPendingAndRecoversWithoutInventingDelivery() throws Exception {
        pending(a, bp);
        var warmup = persistence.claim().orElseThrow();
        sink.publish(warmup); persistence.published(warmup);
        pending(a, profile(outsider));
        var original = jdbc.sql("select id from community_chat_outbox where published_at is null").query(UUID.class).single();
        var relay = new ChatOutboxRelay(persistence, sink, new ChatRelayProperties(true, 100, Duration.ofSeconds(5)));
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            var started = System.nanoTime();
            relay.publishDue();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
            assertThat(published()).isOne();
            assertThat(jdbc.sql("select attempt_count from community_chat_outbox where id = :id").param("id", original).query(Integer.class).single()).isOne();
            assertThat(count("community_chat_request")).isEqualTo(2);
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        clock.advance(Duration.ofSeconds(40));
        relay.publishDue();
        assertThat(published()).isEqualTo(2);
        var replayed = received(original, 1);
        assertThat(replayed).isNotEmpty();
        for (var record : replayed) assertThat(mapper.readTree(record.value()).path("messageId").asText()).isEqualTo(original.toString());
        assertThat(count("community_chat_request")).isEqualTo(2);
    }

    private long published() { return jdbc.sql("select count(*) from community_chat_outbox where published_at is not null").query(Long.class).single(); }
    private List<ConsumerRecord<String, String>> received(UUID id, int expected) {
        var settings = new HashMap<String, Object>();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, "synthetic-mb685-" + UUID.randomUUID());
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(settings)) {
            var partitions = List.of(new TopicPartition(ChatRelayProperties.TOPIC, 0), new TopicPartition(ChatRelayProperties.TOPIC, 1), new TopicPartition(ChatRelayProperties.TOPIC, 2));
            consumer.assign(partitions); consumer.seekToBeginning(partitions);
            var result = new ArrayList<ConsumerRecord<String, String>>();
            long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (result.size() < expected && System.nanoTime() < until) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.value().contains(id.toString())) result.add(record);
                }
            }
            return result;
        }
    }
}
