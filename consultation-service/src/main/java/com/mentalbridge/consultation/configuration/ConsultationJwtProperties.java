package com.mentalbridge.consultation.configuration;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.consultation.jwt")
public record ConsultationJwtProperties(@NotBlank String issuer, @NotBlank String audience,
		@NotBlank String publicKey) {
}
