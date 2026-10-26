package com.mentalbridge.community.configuration;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.community.identity-account-status")
public record IdentityAccountStatusProperties(String baseUrl,
		@Pattern(regexp = "(?:|[!-~]{32,512})") String serviceToken,
		@NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
	public IdentityAccountStatusProperties {
		baseUrl = baseUrl == null ? "" : baseUrl;
		serviceToken = serviceToken == null ? "" : serviceToken;
	}

	@Override
	public String toString() {
		return "IdentityAccountStatusProperties[serviceToken=REDACTED]";
	}
}
