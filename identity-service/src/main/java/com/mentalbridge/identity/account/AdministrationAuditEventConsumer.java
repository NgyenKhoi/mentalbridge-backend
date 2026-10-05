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

            String eventType = root.path("eventType").asText(root.path("messageType").asText(""));
            if (eventType == null || !AdministrationAuditIngestionService.ALLOWED_EVENT_TYPES.containsKey(eventType)) {
                LOGGER.debug("Ignoring unallowlisted audit eventType: {}", eventType);
                return;
            }

            String correlationIdStr = root.path("correlationId").asText(null);
            if (!root.hasNonNull("eventId")) {
                LOGGER.warn("Audit message missing eventId, ignoring: eventType={}, correlationId={}", eventType, correlationIdStr);
                return;
            }

            UUID eventId;
            try {
                eventId = UUID.fromString(root.get("eventId").asText());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Audit message has invalid eventId UUID, ignoring: eventType={}, correlationId={}", eventType, correlationIdStr);
                return;
            }

            JsonNode payload = root.has("payload") && !root.path("payload").isMissingNode()
                    ? root.path("payload")
                    : root;

            // Fail-closed outcome validation per review
            String result = root.hasNonNull("result")
                    ? root.get("result").asText()
                    : (payload.hasNonNull("result") ? payload.get("result").asText() : null);
            if (result == null || (!result.equals("SUCCEEDED") && !result.equals("DENIED") && !result.equals("FAILED"))) {
                LOGGER.warn("Audit message with invalid result, ignoring: eventType={}, eventId={}", eventType, eventId);
                return;
            }

            String action = root.hasNonNull("action")
                    ? root.get("action").asText()
                    : (payload.hasNonNull("action") ? payload.get("action").asText() : null);

            String sourceServiceStr = root.hasNonNull("sourceService")
                    ? root.get("sourceService").asText()
                    : (payload.hasNonNull("sourceService") ? payload.get("sourceService").asText() : null);
            AdministrationAuditService.AuditSourceService sourceService = sourceServiceStr != null
                    ? AdministrationAuditService.safeSourceService(sourceServiceStr)
                    : null;

            String domainStr = root.hasNonNull("domain")
                    ? root.get("domain").asText()
                    : (payload.hasNonNull("domain") ? payload.get("domain").asText() : null);
            AdministrationAuditService.AuditDomain domain = domainStr != null
                    ? AdministrationAuditService.safeDomain(domainStr)
                    : null;

            UUID actorId = null;
            JsonNode actorNode = root.hasNonNull("actorId") ? root.get("actorId") : payload.path("actorId");
            if (actorNode != null && !actorNode.isMissingNode() && !actorNode.isNull()) {
                try {
                    actorId = UUID.fromString(actorNode.asText());
                } catch (IllegalArgumentException ignored) {
                }
            }

            String actorType = root.hasNonNull("actorType")
                    ? root.get("actorType").asText()
                    : (payload.hasNonNull("actorType") ? payload.get("actorType").asText() : "STAFF");

            String reasonCode = root.hasNonNull("reasonCode")
                    ? root.get("reasonCode").asText(null)
                    : (payload.hasNonNull("reasonCode") ? payload.get("reasonCode").asText(null) : null);

            UUID correlationId = null;
            JsonNode corrNode = root.hasNonNull("correlationId") ? root.get("correlationId") : payload.path("correlationId");
            if (corrNode != null && !corrNode.isMissingNode() && !corrNode.isNull()) {
                try {
                    correlationId = UUID.fromString(corrNode.asText());
                } catch (IllegalArgumentException ignored) {
                }
            }

            UUID targetAccountId = null;
            JsonNode targetAccNode = root.hasNonNull("targetAccountId") ? root.get("targetAccountId") : payload.path("targetAccountId");
            if (targetAccNode != null && !targetAccNode.isMissingNode() && !targetAccNode.isNull()) {
                try {
                    targetAccountId = UUID.fromString(targetAccNode.asText());
                } catch (IllegalArgumentException ignored) {
                }
            }

            String targetIdentifier = root.hasNonNull("targetIdentifier")
                    ? root.get("targetIdentifier").asText(null)
                    : (payload.hasNonNull("targetIdentifier") ? payload.get("targetIdentifier").asText(null) : null);

            Instant occurredAt;
            try {
                occurredAt = root.hasNonNull("occurredAt")
                        ? Instant.parse(root.get("occurredAt").asText())
                        : (payload.hasNonNull("occurredAt") ? Instant.parse(payload.get("occurredAt").asText()) : Instant.now());
            } catch (Exception e) {
                occurredAt = Instant.now();
            }

            AdministrationAuditIngestionService.IngestionCommand command =
                    new AdministrationAuditIngestionService.IngestionCommand(
                            eventId,
                            eventType,
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
            LOGGER.warn("Failed to process administration audit event: reason={}", exception.getMessage());
        }
    }
}
