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
		var link = link(properties.verificationUrl(), challenge);
		send(normalizedEmail, correlationId, VerificationEmailTemplate.create(link));
	}

	@Override
	public void requestPasswordRecovery(UUID accountId, String normalizedEmail, String challenge, UUID correlationId) {
		var link = link(properties.passwordRecoveryUrl(), challenge);
		send(normalizedEmail, correlationId, PasswordRecoveryEmailTemplate.create(link));
	}

	private void send(String normalizedEmail, UUID correlationId, VerificationEmailTemplate.Content content) {
		var body = Map.of("sender", Map.of("name", properties.senderName(), "email", properties.senderEmail()),
				"to", List.of(Map.of("email", normalizedEmail)), "subject", content.subject(),
				"htmlContent", content.html(), "textContent", content.text(),
				"headers", Map.of("X-Correlation-Id", correlationId.toString()));
		client.post().uri("/v3/smtp/email").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
	}

	private String link(java.net.URI baseUrl, String challenge) {
		return org.springframework.web.util.UriComponentsBuilder.fromUri(baseUrl).queryParam("challenge", challenge)
				.build().encode().toUriString();
	}

	private void requireConfiguration(VerificationDeliveryProperties configuration) {
		if (configuration.baseUrl() == null || configuration.verificationUrl() == null
				|| configuration.passwordRecoveryUrl() == null
				|| configuration.apiKey() == null || configuration.apiKey().isBlank()
				|| configuration.senderEmail() == null || configuration.senderEmail().isBlank()
				|| configuration.senderName() == null || configuration.senderName().isBlank()) {
			throw new IllegalStateException("Verification delivery configuration is incomplete");
		}
	}
}
