package com.mentalbridge.identity.registration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.mentalbridge.identity.configuration.VerificationDeliveryProperties;
@Component
@ConditionalOnProperty(prefix = "mentalbridge.identity.verification-delivery", name = "mode", havingValue = "brevo")
public class BrevoVerificationDelivery implements VerificationDelivery {

	private final RestClient client;
	private final VerificationDeliveryProperties properties;

	public BrevoVerificationDelivery(RestClient.Builder builder, VerificationDeliveryProperties properties) {
		requireConfiguration(properties);
		this.client = builder.baseUrl(properties.baseUrl().toString()).defaultHeader("api-key", properties.apiKey())
				.build();
		this.properties = properties;
	}

	@Override
	public void requestDelivery(UUID accountId, String normalizedEmail, String challenge, UUID correlationId) {
		var link = properties.verificationUrl().toString() + "?challenge="
				+ java.net.URLEncoder.encode(challenge, java.nio.charset.StandardCharsets.UTF_8);
		var content = VerificationEmailTemplate.create(link);
		var body = Map.of("sender", Map.of("name", properties.senderName(), "email", properties.senderEmail()),
				"to", List.of(Map.of("email", normalizedEmail)), "subject", content.subject(),
				"htmlContent", content.html(), "textContent", content.text(),
				"headers", Map.of("X-Correlation-Id", correlationId.toString()));
		client.post().uri("/v3/smtp/email").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
	}

	private void requireConfiguration(VerificationDeliveryProperties configuration) {
		if (configuration.baseUrl() == null || configuration.verificationUrl() == null
				|| configuration.apiKey() == null || configuration.apiKey().isBlank()
				|| configuration.senderEmail() == null || configuration.senderEmail().isBlank()
				|| configuration.senderName() == null || configuration.senderName().isBlank()) {
			throw new IllegalStateException("Verification delivery configuration is incomplete");
		}
	}
}
