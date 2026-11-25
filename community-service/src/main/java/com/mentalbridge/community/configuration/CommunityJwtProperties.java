package com.mentalbridge.community.configuration;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.community.jwt")
public record CommunityJwtProperties(@NotBlank String issuer, @NotBlank String audience,
		@NotBlank String publicKey) {
}
