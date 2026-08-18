package com.mentalbridge.identity.configuration;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.identity.encryption")
public record EncryptionProperties(@NotBlank String keyVersion, @NotBlank String key) {
}
