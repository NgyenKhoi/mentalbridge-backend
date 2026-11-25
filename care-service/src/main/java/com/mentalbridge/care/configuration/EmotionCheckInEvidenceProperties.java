package com.mentalbridge.care.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("mentalbridge.care.emotion-check-in-evidence")
public record EmotionCheckInEvidenceProperties(
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
	public EmotionCheckInEvidenceProperties {
		if (!positive(connectTimeout) || !positive(readTimeout) || !positive(retryWait)
				|| !positive(circuitOpenDuration) || circuitMinimumCalls > circuitWindowSize
				|| maxAttempts < 1 || maxAttempts > 2
				|| connectTimeout.plus(readTimeout).multipliedBy(maxAttempts)
						.plus(retryWait.multipliedBy(2L * (maxAttempts - 1)))
						.compareTo(Duration.ofMillis(2500)) > 0) {
			throw new IllegalArgumentException("Emotion evidence deadlines and breaker settings are invalid");
		}
	}

	private static boolean positive(Duration value) {
		return value != null && !value.isZero() && !value.isNegative();
	}
}
