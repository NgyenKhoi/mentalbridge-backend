package com.mentalbridge.care.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("mentalbridge.care.consultation-brief-ai-draft")
public record ConsultationBriefAiDraftClientProperties(@DefaultValue("") String baseUrl,
		@DefaultValue("PT0.5S") @NotNull Duration connectTimeout,
		@DefaultValue("PT8S") @NotNull Duration readTimeout) {
	public ConsultationBriefAiDraftClientProperties {
		if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
			throw new IllegalArgumentException("connectTimeout must be positive");
		}
		if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()
				|| readTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
			throw new IllegalArgumentException("readTimeout must be between PT0S and PT10S");
		}
	}
}
