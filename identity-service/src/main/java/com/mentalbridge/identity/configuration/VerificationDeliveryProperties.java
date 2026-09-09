package com.mentalbridge.identity.configuration;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mentalbridge.identity.verification-delivery")
public record VerificationDeliveryProperties(URI baseUrl, String apiKey, String senderEmail,
		String senderName, URI verificationUrl, URI passwordRecoveryUrl) {
}
