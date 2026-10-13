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


        if (maxAttempts != -1 && maxAttempts != FixedBackOff.UNLIMITED_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "Finite retry max-attempts (" + maxAttempts + ") is not supported for administration audit ingestion. " +
                    "Audit events require durable unlimited retain-and-retry (mentalbridge.identity.audit-ingestion.max-attempts must be -1 or omitted) " +
                    "to prevent silent loss during persistence outages.");
        }

        if (retryInterval <= 0) {
            throw new IllegalArgumentException("mentalbridge.identity.audit-ingestion.retry-interval must be positive: " + retryInterval);
        }

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);

        BackOff backOff = new FixedBackOff(retryInterval, FixedBackOff.UNLIMITED_ATTEMPTS);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, exception) -> {
            LOGGER.error("Audit ingestion retain-and-retry encountered unrecoverable condition at topic {} partition {} offset {}: {}",
                    record.topic(), record.partition(), record.offset(), exception.getMessage());
            throw new IllegalStateException("Audit ingestion recoverer should not be reached in unlimited-retry mode", exception);
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

