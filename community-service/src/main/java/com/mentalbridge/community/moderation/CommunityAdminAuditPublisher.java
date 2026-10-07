package com.mentalbridge.community.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.community.admin-audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommunityAdminAuditPublisher {

	private static final Logger LOGGER = LoggerFactory.getLogger(CommunityAdminAuditPublisher.class);

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;
	private final String topic;

	public CommunityAdminAuditPublisher(
			ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
			ObjectMapper objectMapper,
			@Value("${mentalbridge.community.admin-audit.topic:mentalbridge.admin.audit-event.v1}") String topic) {
		this.kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
		this.objectMapper = objectMapper;
		this.topic = topic;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onCommunityAdminAuditEvent(CommunityAdminAuditEvent event) {
		if (kafkaTemplate == null) {
			LOGGER.debug("KafkaTemplate not available, skipping audit event publish: eventId={}", event.eventId());
			return;
		}
		try {
			String json = objectMapper.writeValueAsString(event);
			kafkaTemplate.send(topic, event.targetAccountId() != null ? event.targetAccountId().toString() : event.eventId().toString(), json);
			LOGGER.info("Published community administration audit event: eventId={}, eventType={}, targetAccountId={}",
					event.eventId(), event.eventType(), event.targetAccountId());
		}
		catch (Exception exception) {
			LOGGER.error("Failed to publish community administration audit event: eventId={}, eventType={}",
					event.eventId(), event.eventType(), exception);
		}
	}
}

