package com.mentalbridge.identity.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(prefix = "mentalbridge.identity.audit-ingestion", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdministrationAuditKafkaConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdministrationAuditKafkaConfiguration.class);

    @Bean(name = "administrationAuditListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> administrationAuditListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            @Value("${mentalbridge.identity.audit-ingestion.retry-interval:1000}") long retryInterval,
            @Value("${mentalbridge.identity.audit-ingestion.max-attempts:-1}") long maxAttempts) {

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);

        long attempts = maxAttempts <= 0 ? FixedBackOff.UNLIMITED_ATTEMPTS : maxAttempts;
        BackOff backOff = new FixedBackOff(retryInterval, attempts);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, exception) -> {
            LOGGER.error("Audit ingestion retain-and-retry failed at topic {} partition {} offset {}. Record will remain uncommitted and retried: {}",
                    record.topic(), record.partition(), record.offset(), exception.getMessage());
        }, backOff);

        // Crucial retain-and-retry durability invariants:
        // Do NOT commit recovered offset or acknowledge failed event if database persistence is unavailable
        errorHandler.setCommitRecovered(false);
        errorHandler.setAckAfterHandle(false);
        errorHandler.setSeekAfterError(true);

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}

