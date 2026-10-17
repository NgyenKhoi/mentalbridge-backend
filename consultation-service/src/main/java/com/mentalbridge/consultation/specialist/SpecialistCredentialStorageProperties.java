package com.mentalbridge.consultation.specialist;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.consultation.credentials.cloudinary")
public record SpecialistCredentialStorageProperties(boolean enabled, String cloudName, String apiKey,
		String apiSecret, @Min(1) @Max(30) int timeoutSeconds) { }
