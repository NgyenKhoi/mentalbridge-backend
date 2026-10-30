package com.mentalbridge.care.configuration;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.care.identity-account-status")
public record IdentityAccountStatusProperties(String baseUrl,
		@Pattern(regexp = "(?:|[!-~]{32,512})") String serviceToken,
		@NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
	public IdentityAccountStatusProperties {
		baseUrl = baseUrl == null ? "" : baseUrl;
		serviceToken = serviceToken == null ? "" : serviceToken;
	}

	@AssertTrue(message = "account-status timeouts must be at least 1ms")
	public boolean isTimeoutConfigurationValid() {
		return connectTimeout != null && readTimeout != null
				&& connectTimeout.compareTo(Duration.ofMillis(1)) >= 0
				&& readTimeout.compareTo(Duration.ofMillis(1)) >= 0;
	}

	@Override public String toString() { return "IdentityAccountStatusProperties[serviceToken=REDACTED]"; }
}
