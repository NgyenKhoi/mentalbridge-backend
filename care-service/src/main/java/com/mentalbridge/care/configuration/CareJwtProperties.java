package com.mentalbridge.care.configuration;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.care.jwt")
public record CareJwtProperties(@NotBlank String issuer, @NotBlank String audience,
		@NotBlank String publicKey) {
}
