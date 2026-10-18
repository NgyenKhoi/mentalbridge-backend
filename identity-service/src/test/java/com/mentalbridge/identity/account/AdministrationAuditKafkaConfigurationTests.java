package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

class AdministrationAuditKafkaConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AdministrationAuditKafkaConfiguration.class)
            .withBean(ConcurrentKafkaListenerContainerFactoryConfigurer.class, () -> mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class))
            .withBean(ConsumerFactory.class, () -> mock(ConsumerFactory.class));

    @Test
    void positiveMaxAttemptsFailsStartupFastWithExplicitError() {
        contextRunner
                .withPropertyValues("mentalbridge.identity.audit-ingestion.max-attempts=5")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .getRootCause()
                            .hasMessageContaining("Finite retry max-attempts (5) is not supported for administration audit ingestion")
                            .hasMessageContaining("must be -1 or omitted");
                });
    }

    @Test
    void zeroMaxAttemptsFailsStartupFast() {
        contextRunner
                .withPropertyValues("mentalbridge.identity.audit-ingestion.max-attempts=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .getRootCause()
                            .hasMessageContaining("Finite retry max-attempts (0) is not supported");
                });
    }

    @Test
    void negativeRetryIntervalFailsStartupFast() {
        contextRunner
                .withPropertyValues("mentalbridge.identity.audit-ingestion.retry-interval=-500")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .getRootCause()
                            .hasMessageContaining("retry-interval must be positive");
                });
    }

    @Test
    void unlimitedRetryConfigurationSucceedsWithDefaults() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("administrationAuditListenerContainerFactory");
                    ConcurrentKafkaListenerContainerFactory<?, ?> factory =
                            context.getBean("administrationAuditListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(factory).isNotNull();
                });
    }

    @Test
    void explicitMinusOneUnlimitedRetryConfigurationSucceeds() {
        contextRunner
                .withPropertyValues("mentalbridge.identity.audit-ingestion.max-attempts=-1",
                        "mentalbridge.identity.audit-ingestion.retry-interval=250")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("administrationAuditListenerContainerFactory");
                });
    }

    @Test
    void directMethodCallRejectsUnsafeFiniteRetry() {
        AdministrationAuditKafkaConfiguration config = new AdministrationAuditKafkaConfiguration();
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer = mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        @SuppressWarnings("unchecked")
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);

        assertThatThrownBy(() -> config.administrationAuditListenerContainerFactory(configurer, consumerFactory, 1000, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Finite retry max-attempts (3) is not supported");

        assertThatThrownBy(() -> config.administrationAuditListenerContainerFactory(configurer, consumerFactory, -10, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retry-interval must be positive");
    }
}
