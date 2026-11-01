package com.mentalbridge.care.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.care.voice-consent")
public record VoiceConsentProperties(@Pattern(regexp = "(?:|[!-~]{32,256})") String journalServiceToken,
		@NotBlank @Size(min = 32) String idempotencyHmacKey) {

	public VoiceConsentProperties {
		journalServiceToken = journalServiceToken == null ? "" : journalServiceToken;
	}

	public boolean authorizes(String candidate) {
		return journalServiceToken.length() >= 32 && candidate != null && MessageDigest.isEqual(
				journalServiceToken.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
	}

	public void requireDistinctCredential(String otherCredential) {
		if (!journalServiceToken.isEmpty() && (journalServiceToken.equals(otherCredential)
				|| journalServiceToken.equals(idempotencyHmacKey))) {
			throw new IllegalArgumentException("Care voice credential must be distinct from other purpose credentials");
		}
	}

	@Override
	public String toString() {
		return "VoiceConsentProperties[journalServiceToken=REDACTED,idempotencyHmacKey=REDACTED]";
	}
}
