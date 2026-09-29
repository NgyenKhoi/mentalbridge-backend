package com.mentalbridge.community.configuration;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.community.cloudinary")
public record CloudinaryProperties(@NotBlank String cloudName, @NotBlank String apiKey,
		@NotBlank String apiSecret) {
}
