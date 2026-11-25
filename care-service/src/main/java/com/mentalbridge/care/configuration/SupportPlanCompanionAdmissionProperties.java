package com.mentalbridge.care.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("mentalbridge.care.support-plan-companion-admission")
public record SupportPlanCompanionAdmissionProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("companion-admission-v1") @NotBlank String policyVersion,
		@DefaultValue("6") @Min(1) @Max(120) int maximumRequests,
		@DefaultValue("PT1M") @NotNull Duration window,
		@DefaultValue("PT12S") @NotNull Duration pendingTtl,
		@DefaultValue("mentalbridge:care:support-plan-companion") @NotBlank String keyPrefix) {
	public SupportPlanCompanionAdmissionProperties {
		if (window == null || window.isZero() || window.isNegative()) {
			throw new IllegalArgumentException("window must be positive");
		}
		if (pendingTtl == null || pendingTtl.isZero() || pendingTtl.isNegative()) {
			throw new IllegalArgumentException("pendingTtl must be positive");
		}
	}
}
