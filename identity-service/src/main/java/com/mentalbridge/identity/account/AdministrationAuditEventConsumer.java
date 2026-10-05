package com.mentalbridge.identity.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.identity.audit-ingestion", name = "enabled", havingValue = "true")
public class AdministrationAuditEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdministrationAuditEventConsumer.class);

    private final AdministrationAuditIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    public AdministrationAuditEventConsumer(
            AdministrationAuditIngestionService ingestionService,
            ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${mentalbridge.identity.audit-ingestion.topic:mentalbridge.admin.audit-event.v1}",
            groupId = "${IDENTITY_AUDIT_CONSUMER_GROUP:mentalbridge.identity.audit-consumer}"
    )
    public void onMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);

            String messageType = root.path("messageType").asText(root.path("eventType").asText(""));
            if (messageType == null || !AdministrationAuditIngestionService.ALLOWED_MESSAGE_TYPES.contains(messageType)) {
                LOGGER.debug("Ignoring unallowlisted audit message type: {}", messageType);
                return;
            }

            if (!root.hasNonNull("eventId")) {
                LOGGER.warn("Audit message missing eventId, ignoring: {}", message);
                return;
            }

            UUID eventId = UUID.fromString(root.get("eventId").asText());

            Instant occurredAt = root.hasNonNull("occurredAt")
                    ? Instant.parse(root.get("occurredAt").asText())
                    : Instant.now();

            JsonNode payload = root.has("payload") && !root.path("payload").isMissingNode()
                    ? root.path("payload")
                    : root;

            String action = payload.path("action").asText(null);
            if (action == null || action.isBlank()) {
                LOGGER.warn("Audit message missing action, ignoring: {}", message);
                return;
            }

            String sourceServiceStr = payload.path("sourceService").asText(null);
            if (sourceServiceStr == null || sourceServiceStr.isBlank()) {
                if (messageType.startsWith("consultation.")) {
                    sourceServiceStr = "CONSULTATION";
                } else if (messageType.startsWith("content.")) {
                    sourceServiceStr = "CONTENT";
                } else if (messageType.startsWith("community.")) {
                    sourceServiceStr = "COMMUNITY";
                } else {
                    sourceServiceStr = "IDENTITY";
                }
            }
            AdministrationAuditService.AuditSourceService sourceService =
                    AdministrationAuditService.safeSourceService(sourceServiceStr);

            String domainStr = payload.path("domain").asText(null);
            if (domainStr == null || domainStr.isBlank()) {
                if (sourceService == AdministrationAuditService.AuditSourceService.CONSULTATION) {
                    domainStr = "SPECIALIST_REVIEW";
                } else if (sourceService == AdministrationAuditService.AuditSourceService.CONTENT) {
                    domainStr = "RESOURCE_MANAGEMENT";
                } else if (sourceService == AdministrationAuditService.AuditSourceService.COMMUNITY) {
                    domainStr = "COMMUNITY_MODERATION";
                } else {
                    domainStr = "ACCOUNT_ADMINISTRATION";
                }
            }
            AdministrationAuditService.AuditDomain domain =
                    AdministrationAuditService.safeDomain(domainStr);

            UUID actorId = null;
            if (payload.hasNonNull("actorId")) {
                try {
                    actorId = UUID.fromString(payload.get("actorId").asText());
                } catch (IllegalArgumentException ignored) {
                }
            }

            String actorType = payload.hasNonNull("actorType") ? payload.get("actorType").asText() : "STAFF";
            String result = payload.hasNonNull("result") ? payload.get("result").asText() : "SUCCEEDED";
            String reasonCode = payload.hasNonNull("reasonCode") ? payload.get("reasonCode").asText() : null;

            UUID correlationId = null;
            if (root.hasNonNull("correlationId")) {
                try {
                    correlationId = UUID.fromString(root.get("correlationId").asText());
                } catch (IllegalArgumentException ignored) {
                }
            } else if (payload.hasNonNull("correlationId")) {
                try {
                    correlationId = UUID.fromString(payload.get("correlationId").asText());
                } catch (IllegalArgumentException ignored) {
                }
            }

            UUID targetAccountId = null;
            if (payload.hasNonNull("targetAccountId")) {
                try {
                    targetAccountId = UUID.fromString(payload.get("targetAccountId").asText());
                } catch (IllegalArgumentException ignored) {
                }
            }

            String targetIdentifier = payload.hasNonNull("targetIdentifier")
                    ? payload.get("targetIdentifier").asText()
                    : null;

            AdministrationAuditIngestionService.IngestionCommand command =
                    new AdministrationAuditIngestionService.IngestionCommand(
                            eventId,
                            occurredAt,
                            sourceService,
                            domain,
                            actorId,
                            actorType,
                            action,
                            result,
                            reasonCode,
                            correlationId,
                            targetAccountId,
                            targetIdentifier
                    );

            ingestionService.ingest(command);
        } catch (Exception exception) {
            LOGGER.warn("Failed to process administration audit event: {}", exception.getMessage(), exception);
        }
    }
}
