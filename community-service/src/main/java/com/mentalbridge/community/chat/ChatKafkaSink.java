package com.mentalbridge.community.chat;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.community.chat-relay", name = "enabled", havingValue = "true")
class ChatKafkaSink {
    private final DefaultKafkaProducerFactory<String, String> factory;
    private final KafkaTemplate<String, String> kafka;

    ChatKafkaSink(KafkaTemplate<String, String> existing, ChatProperties chat) {
        chat.requireEnabled();
        var config = new HashMap<String, Object>(existing.getProducerFactory().getConfigurationProperties());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        factory = new DefaultKafkaProducerFactory<>(config);
        kafka = new KafkaTemplate<>(factory);
        kafka.setProducerListener(new org.springframework.kafka.support.ProducerListener<>() {});
    }

    void publish(ChatRelayPersistence.PendingFact fact) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Chat relay cannot send in a database transaction");
        kafka.send(ChatRelayProperties.TOPIC, fact.conversationId().toString(), fact.payload().toString()).get(4, TimeUnit.SECONDS);
    }

    @PreDestroy
    void close() { factory.destroy(); }
}
