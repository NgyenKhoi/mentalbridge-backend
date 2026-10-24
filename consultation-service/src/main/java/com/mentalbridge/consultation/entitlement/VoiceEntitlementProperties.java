package com.mentalbridge.consultation.entitlement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mentalbridge.consultation.voice")
public record VoiceEntitlementProperties(String journalServiceToken) {

	public VoiceEntitlementProperties {
		if (journalServiceToken != null && !journalServiceToken.isEmpty()
				&& !journalServiceToken.matches("[!-~]{32,256}")) {
			throw new IllegalArgumentException("Voice Journal service token must be 32-256 printable ASCII characters without whitespace");
		}
	}

	public void requireDistinctFrom(String... existingCredentials) {
		if (journalServiceToken == null || journalServiceToken.isEmpty()) return;
		for (var existing : existingCredentials) {
			if (authenticates(existing)) {
				throw new IllegalArgumentException("Voice Journal service token must be purpose-specific");
			}
		}
	}

	boolean authenticates(String candidate) {
		return journalServiceToken != null && !journalServiceToken.isBlank() && candidate != null
				&& MessageDigest.isEqual(journalServiceToken.getBytes(StandardCharsets.UTF_8),
						candidate.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public String toString() {
		return "VoiceEntitlementProperties[journalServiceToken=REDACTED]";
	}
}
