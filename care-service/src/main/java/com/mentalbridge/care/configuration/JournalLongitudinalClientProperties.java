package com.mentalbridge.care.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("mentalbridge.care.journal-longitudinal")
public record JournalLongitudinalClientProperties(
		@DefaultValue("") String baseUrl,
		@DefaultValue("PT0.2S") @NotNull Duration connectTimeout,
		@DefaultValue("PT0.8S") @NotNull Duration readTimeout,
		@DefaultValue("2") @Min(1) @Max(2) int maxAttempts,
		@DefaultValue("PT0.1S") @NotNull Duration retryWait,
		@DefaultValue("10") @Min(2) @Max(100) int circuitWindowSize,
		@DefaultValue("5") @Min(1) @Max(100) int circuitMinimumCalls,
		@DefaultValue("50") @Min(1) @Max(100) int circuitFailureRate,
		@DefaultValue("PT10S") @NotNull Duration circuitOpenDuration,
		@DefaultValue("2") @Min(1) @Max(20) int circuitHalfOpenCalls) {
	private static final Duration MAXIMUM_CALL_BUDGET = Duration.ofMillis(2500);

	public JournalLongitudinalClientProperties {
		if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
			throw new IllegalArgumentException("connectTimeout must be positive");
		}
		if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
			throw new IllegalArgumentException("readTimeout must be positive");
		}
		if (retryWait == null || retryWait.isZero() || retryWait.isNegative()) {
			throw new IllegalArgumentException("retryWait must be positive");
		}
		if (circuitOpenDuration == null || circuitOpenDuration.isZero() || circuitOpenDuration.isNegative()) {
			throw new IllegalArgumentException("circuitOpenDuration must be positive");
		}
		if (circuitMinimumCalls > circuitWindowSize) {
			throw new IllegalArgumentException("circuitMinimumCalls cannot exceed circuitWindowSize");
		}
		if (maxAttempts >= 1) {
			Duration attempts = connectTimeout.plus(readTimeout).multipliedBy(maxAttempts);
			Duration conservativeRetryWait = retryWait.multipliedBy(Math.max(0L, 2L * (maxAttempts - 1L)));
			if (attempts.plus(conservativeRetryWait).compareTo(MAXIMUM_CALL_BUDGET) > 0) {
				throw new IllegalArgumentException("Journal/AI call budget must not exceed PT2.5S");
			}
		}
	}
}
