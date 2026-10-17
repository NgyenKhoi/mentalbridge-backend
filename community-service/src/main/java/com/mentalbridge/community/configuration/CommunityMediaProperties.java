package com.mentalbridge.community.configuration;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.community.media")
public record CommunityMediaProperties(
		@Min(1) long maxImageBytes,
		@Min(1) long maxVideoBytes,
		@Min(1) @Max(600) int maxVideoDurationSeconds,
		@NotNull Duration uploadIntentTtl,
		@NotNull Duration orphanRetention,
		@NotNull Duration cleanupInterval) {
}
