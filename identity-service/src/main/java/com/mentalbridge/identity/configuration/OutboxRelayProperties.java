package com.mentalbridge.identity.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("mentalbridge.identity.outbox-relay")
public record OutboxRelayProperties(boolean enabled, @NotBlank String topic,
		@Min(1) @Max(500) int batchSize, @NotNull Duration interval,
		@NotNull Duration sendTimeout, @NotNull Duration retryBase,
		@NotNull Duration retryMaximum) {
}
