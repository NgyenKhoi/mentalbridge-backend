package com.mentalbridge.community.notification;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.community.interaction-relay", name = "enabled",
		havingValue = "true")
class KafkaCommunityInteractionEventSink implements CommunityInteractionEventSink {

	private final CommunityInteractionRelayProperties properties;
	private final KafkaTemplate<String, String> kafka;
	private final ObjectMapper objectMapper;

	KafkaCommunityInteractionEventSink(CommunityInteractionRelayProperties properties,
			KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
		this.properties = properties;
		this.kafka = kafka;
		this.objectMapper = objectMapper;
	}

	@Override
	public void publish(UUID targetId, JsonNode eventPayload) throws Exception {
		kafka.send(properties.topic(), targetId.toString(), objectMapper.writeValueAsString(eventPayload))
				.get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
	}
}
