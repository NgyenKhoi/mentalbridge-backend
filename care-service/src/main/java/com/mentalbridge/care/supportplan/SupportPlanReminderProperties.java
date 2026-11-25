package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Content-to-Care credential for the daily SupportPlan reminder read; empty disables the endpoint. */
@Validated
@ConfigurationProperties("mentalbridge.care.support-plan-reminder")
public record SupportPlanReminderProperties(@Pattern(regexp = "(?:|[!-~]{32,256})") String serviceToken) {

	public SupportPlanReminderProperties {
		serviceToken = serviceToken == null ? "" : serviceToken;
	}

	public boolean authorizes(String candidate) {
		return serviceToken.length() >= 32 && candidate != null && MessageDigest.isEqual(
				serviceToken.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
	}

	public void requireDistinctCredential(String... otherCredentials) {
		if (serviceToken.isEmpty()) return;
		for (String other : otherCredentials) {
			if (serviceToken.equals(other)) {
				throw new IllegalArgumentException(
						"Care SupportPlan reminder credential must be distinct from other purpose credentials");
			}
		}
	}

	@Override
	public String toString() {
		return "SupportPlanReminderProperties[serviceToken=REDACTED]";
	}
}
