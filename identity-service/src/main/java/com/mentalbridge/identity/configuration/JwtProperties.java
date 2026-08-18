package com.mentalbridge.identity.configuration;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.identity.jwt")
public record JwtProperties(@NotBlank String issuer, @NotBlank String audience, @NotBlank String keyId,
		@NotBlank String privateKey, @NotBlank String publicKey) {
}
