package com.mentalbridge.care.configuration;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.care.assessment")
public record AssessmentProperties(@NotNull Duration anonymousSessionTtl,
		@NotBlank String phq9SafetyPolicyVersion,
		@NotBlank @Size(min = 32) String idempotencyHmacKey) {

	public AssessmentProperties {
		if (anonymousSessionTtl != null && (anonymousSessionTtl.isZero() || anonymousSessionTtl.isNegative())) {
			throw new IllegalArgumentException("Anonymous assessment session TTL must be positive");
		}
	}

	@Override
	public String toString() {
		return "AssessmentProperties[anonymousSessionTtl=" + anonymousSessionTtl
				+ ", phq9SafetyPolicyVersion=" + phq9SafetyPolicyVersion + ", idempotencyHmacKey=[REDACTED]]";
	}
}
