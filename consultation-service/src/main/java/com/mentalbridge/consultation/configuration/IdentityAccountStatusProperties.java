package com.mentalbridge.consultation.configuration;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.consultation.identity-account-status")
public record IdentityAccountStatusProperties(String baseUrl,
		@Pattern(regexp = "(?:|[!-~]{32,512})") String serviceToken,
		@NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
	public IdentityAccountStatusProperties {
		baseUrl = baseUrl == null ? "" : baseUrl;
		serviceToken = serviceToken == null ? "" : serviceToken;
	}

	@AssertTrue(message = "account-status connect timeout must be 1-250ms and read timeout 1-750ms")
	public boolean isTimeoutConfigurationValid() {
		return connectTimeout != null && readTimeout != null
				&& connectTimeout.compareTo(Duration.ofMillis(1)) >= 0
				&& connectTimeout.compareTo(Duration.ofMillis(250)) <= 0
				&& readTimeout.compareTo(Duration.ofMillis(1)) >= 0
				&& readTimeout.compareTo(Duration.ofMillis(750)) <= 0;
	}

	@Override public String toString() { return "IdentityAccountStatusProperties[serviceToken=REDACTED]"; }
}
