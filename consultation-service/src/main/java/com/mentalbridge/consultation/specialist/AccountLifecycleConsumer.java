package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class AccountLifecycleConsumer {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountLifecycleConsumer.class);
	public static final String ACCOUNT_STATE_CHANGED = "identity.account.state-changed";

	private final SpecialistAccountLifecycleService lifecycleService;
	private final ObjectMapper objectMapper;

	public AccountLifecycleConsumer(SpecialistAccountLifecycleService lifecycleService, ObjectMapper objectMapper) {
		this.lifecycleService = lifecycleService;
		this.objectMapper = objectMapper;
	}

	@KafkaListener(topics = "${mentalbridge.consultation.account-lifecycle.topic:mentalbridge.identity.account-lifecycle.v1}")
	public void onMessage(String message) {
		try {
			JsonNode root = objectMapper.readTree(message);
			String messageType = root.path("messageType").asText();
			if (!ACCOUNT_STATE_CHANGED.equals(messageType)) {
				LOGGER.debug("Ignoring unsupported message type: {}", messageType);
				return;
			}
			Instant occurredAt = root.hasNonNull("occurredAt")
					? Instant.parse(root.get("occurredAt").asText())
					: Instant.now();
			JsonNode payload = root.path("payload");
			UUID accountId = UUID.fromString(payload.path("accountId").asText());
			String status = payload.path("status").asText();
			String role = payload.path("role").asText();
			String reasonCode = payload.path("reasonCode").asText();

			lifecycleService.handleAccountStateChanged(accountId, status, role, reasonCode, occurredAt);
		}
		catch (Exception exception) {
			LOGGER.error("Failed to process account lifecycle message: {}", message, exception);
			throw new RuntimeException("Account lifecycle consumption failed", exception);
		}
	}
}
