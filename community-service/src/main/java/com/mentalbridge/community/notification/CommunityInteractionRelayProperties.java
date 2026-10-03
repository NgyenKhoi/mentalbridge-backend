package com.mentalbridge.community.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("mentalbridge.community.interaction-relay")
public record CommunityInteractionRelayProperties(boolean enabled, @NotBlank String topic,
		@Min(1) @Max(500) int batchSize, @NotNull Duration interval, @NotNull Duration sendTimeout,
		@NotNull Duration retryBase, @NotNull Duration retryMaximum) {
}
