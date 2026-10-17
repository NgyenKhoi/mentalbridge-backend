package com.mentalbridge.consultation.specialist;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class CloudinarySpecialistCredentialStorageTests {

	@Test
	void signsAnAuthenticatedOriginalWithAnExplicitExpiry() {
		var storage = new CloudinarySpecialistCredentialStorage(
				new SpecialistCredentialStorageProperties(true, "example-cloud", "example-key", "example-secret", 5));
		var expiry = Instant.parse("2026-10-17T01:45:00Z");

		var url = storage.viewUrl("specialist-credentials/owner/document.pdf", "pdf",
				"application/pdf", expiry, false);

		assertThat(url).startsWith("https://");
		assertThat(url).contains("type=authenticated", "expires_at=" + expiry.getEpochSecond(),
				"signature=", "public_id=");
	}
}
