package com.mentalbridge.care.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("mentalbridge.care.entitlement")
public record EntitlementClientProperties(
		@DefaultValue("") String baseUrl,
		@DefaultValue("PT0.5S") @NotNull Duration connectTimeout,
		@DefaultValue("PT2S") @NotNull Duration readTimeout) {

	public EntitlementClientProperties {
		if (connectTimeout != null && (connectTimeout.isZero() || connectTimeout.isNegative())) {
			throw new IllegalArgumentException("connectTimeout must be positive");
		}
		if (readTimeout != null && (readTimeout.isZero() || readTimeout.isNegative())) {
			throw new IllegalArgumentException("readTimeout must be positive");
		}
	}
}
