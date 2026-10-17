package com.mentalbridge.care.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("mentalbridge.care.resource-eligibility")
public record ResourceEligibilityClientProperties(
		@DefaultValue("") String baseUrl,
		@DefaultValue("PT0.5S") @NotNull Duration connectTimeout,
		@DefaultValue("PT2S") @NotNull Duration readTimeout,
		@DefaultValue("2") @Min(1) @Max(3) int maxAttempts,
		@DefaultValue("PT0.1S") @NotNull Duration retryWait,
		@DefaultValue("10") @Min(2) @Max(100) int circuitWindowSize,
		@DefaultValue("5") @Min(1) @Max(100) int circuitMinimumCalls,
		@DefaultValue("50") @Min(1) @Max(100) int circuitFailureRate,
		@DefaultValue("PT10S") @NotNull Duration circuitOpenDuration,
		@DefaultValue("2") @Min(1) @Max(20) int circuitHalfOpenCalls) {

	public ResourceEligibilityClientProperties {
		if (connectTimeout != null && (connectTimeout.isZero() || connectTimeout.isNegative())) {
			throw new IllegalArgumentException("connectTimeout must be positive");
		}
		if (readTimeout != null && (readTimeout.isZero() || readTimeout.isNegative())) {
			throw new IllegalArgumentException("readTimeout must be positive");
		}
		if (retryWait != null && (retryWait.isZero() || retryWait.isNegative())) {
			throw new IllegalArgumentException("retryWait must be positive");
		}
		if (circuitOpenDuration != null && (circuitOpenDuration.isZero() || circuitOpenDuration.isNegative())) {
			throw new IllegalArgumentException("circuitOpenDuration must be positive");
		}
		if (circuitMinimumCalls > circuitWindowSize) {
			throw new IllegalArgumentException("circuitMinimumCalls cannot exceed circuitWindowSize");
		}
	}
}
