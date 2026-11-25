package com.mentalbridge.community.configuration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.community.cloudinary")
public record CloudinaryProperties(@NotBlank String cloudName, @NotBlank String apiKey,
		@NotBlank String apiSecret, @Min(1) @Max(30) int timeoutSeconds) {
}
