package com.mentalbridge.identity.configuration;

import java.net.URI;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mentalbridge.identity.verification-delivery")
public record VerificationDeliveryProperties(Mode mode, URI baseUrl, String apiKey, String senderEmail,
		String senderName, URI verificationUrl, Path localDirectory) {

	public enum Mode {
		DISABLED,
		BREVO,
		LOCAL_FILE
	}

}
