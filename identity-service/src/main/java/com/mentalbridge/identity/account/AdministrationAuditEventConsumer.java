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

    private static final java.util.Set<String> ALLOWED_TOP_LEVEL_FIELDS = java.util.Set.of(
            "eventId",
            "eventType",
            "occurredAt",
            "producer",
            "schemaVersion",
            "sourceService",
            "domain",
            "actorId",
            "actorType",
            "action",
            "result",
            "reasonCode",
            "correlationId",
            "targetAccountId",
            "targetIdentifier"
    );

    @KafkaListener(
            topics = "${mentalbridge.identity.audit-ingestion.topic:mentalbridge.admin.audit-event.v1}",
            groupId = "${IDENTITY_AUDIT_CONSUMER_GROUP:mentalbridge.identity.audit-consumer}"
    )
    public void onMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (!root.isObject()) {
                LOGGER.warn("Audit message is not a JSON object, ignoring");
                return;
            }

            // Reject unexpected fields fail-closed (additionalProperties: false per schema v1)
            var fieldIterator = root.fieldNames();
            while (fieldIterator.hasNext()) {
                String fieldName = fieldIterator.next();
                if (!ALLOWED_TOP_LEVEL_FIELDS.contains(fieldName)) {
                    LOGGER.warn("Audit message contains unexpected field '{}', rejecting fail-closed: eventType={}, correlationId={}",
                            fieldName, root.path("eventType").asText(null), root.path("correlationId").asText(null));
                    return;
                }
            }

            // Required fields per schema v1: eventId, eventType, occurredAt, sourceService, domain, action, result, correlationId
            if (!root.hasNonNull("eventId") || !root.hasNonNull("eventType") || !root.hasNonNull("occurredAt")
                    || !root.hasNonNull("sourceService") || !root.hasNonNull("domain")
                    || !root.hasNonNull("action") || !root.hasNonNull("result") || !root.hasNonNull("correlationId")) {
                LOGGER.warn("Audit message missing required fields, ignoring: eventType={}, correlationId={}",
                        root.path("eventType").asText(null), root.path("correlationId").asText(null));
                return;
            }

            // correlationId
            UUID correlationId;
            try {
                correlationId = UUID.fromString(root.get("correlationId").asText());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Audit message has invalid correlationId UUID, ignoring: eventType={}",
                        root.path("eventType").asText(null));
                return;
            }

            // eventId
            UUID eventId;
            try {
                eventId = UUID.fromString(root.get("eventId").asText());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Audit message has invalid eventId UUID, ignoring: correlationId={}", correlationId);
                return;
            }

            // eventType
            String eventType = root.get("eventType").asText();
            if (!AdministrationAuditIngestionService.ALLOWED_EVENT_TYPES.containsKey(eventType)) {
                LOGGER.debug("Ignoring unallowlisted audit eventType: {}", eventType);
                return;
            }

            // schemaVersion (if present, must be 1.0)
            if (root.has("schemaVersion")) {
                String schemaVersion = root.get("schemaVersion").asText();
                if (!"1.0".equals(schemaVersion)) {
                    LOGGER.warn("Audit message has unsupported schemaVersion '{}', ignoring: eventType={}, eventId={}",
                            schemaVersion, eventType, eventId);
                    return;
                }
            }

            // result (fail-closed)
            String result = root.get("result").asText();
            if (!result.equals("SUCCEEDED") && !result.equals("DENIED") && !result.equals("FAILED")) {
                LOGGER.warn("Audit message with invalid result, ignoring: eventType={}, eventId={}", eventType, eventId);
                return;
            }

            // action
            String action = root.get("action").asText();
            if (action.isBlank() || !action.matches("^[A-Z0-9_]+$")) {
                LOGGER.warn("Audit message missing or invalid action, ignoring: eventType={}, eventId={}", eventType, eventId);
                return;
            }

            // sourceService
            String sourceServiceStr = root.get("sourceService").asText();
            AdministrationAuditService.AuditSourceService sourceService =
                    AdministrationAuditService.safeSourceService(sourceServiceStr);
            if (sourceService == null) {
                LOGGER.warn("Audit message has invalid sourceService enum '{}', ignoring: eventType={}, eventId={}",
                        sourceServiceStr, eventType, eventId);
                return;
            }

            // domain
            String domainStr = root.get("domain").asText();
            AdministrationAuditService.AuditDomain domain =
                    AdministrationAuditService.safeDomain(domainStr);
            if (domain == null) {
                LOGGER.warn("Audit message has invalid domain enum '{}', ignoring: eventType={}, eventId={}",
                        domainStr, eventType, eventId);
                return;
            }

            // occurredAt
            Instant occurredAt;
            try {
                occurredAt = Instant.parse(root.get("occurredAt").asText());
            } catch (Exception e) {
                LOGGER.warn("Audit message has invalid occurredAt timestamp, ignoring: eventType={}, eventId={}",
                        eventType, eventId);
                return;
            }

            // actorId & actorType
            UUID actorId = null;
            if (root.hasNonNull("actorId")) {
                try {
                    actorId = UUID.fromString(root.get("actorId").asText());
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Audit message has invalid actorId UUID, ignoring: eventType={}, eventId={}",
                            eventType, eventId);
                    return;
                }
            }

            String actorType = null;
            if (root.hasNonNull("actorType")) {
                actorType = root.get("actorType").asText();
                if (!"ADMIN".equals(actorType) && !"SYSTEM".equals(actorType)) {
                    LOGGER.warn("Audit message has invalid actorType '{}', ignoring: eventType={}, eventId={}",
                            actorType, eventType, eventId);
                    return;
                }
            } else {
                actorType = actorId != null ? "ADMIN" : "SYSTEM";
            }

            if ("SYSTEM".equals(actorType) && actorId != null) {
                LOGGER.warn("Audit message has actorType=SYSTEM but non-null actorId, ignoring: eventType={}, eventId={}",
                        eventType, eventId);
                return;
            }
            if ("ADMIN".equals(actorType) && actorId == null) {
                LOGGER.warn("Audit message has actorType=ADMIN but null actorId, ignoring: eventType={}, eventId={}",
                        eventType, eventId);
                return;
            }

            // reasonCode
            String reasonCode = root.hasNonNull("reasonCode") ? root.get("reasonCode").asText(null) : null;

            // targetAccountId
            UUID targetAccountId = null;
            if (root.hasNonNull("targetAccountId")) {
                try {
                    targetAccountId = UUID.fromString(root.get("targetAccountId").asText());
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Audit message has invalid targetAccountId UUID, ignoring: eventType={}, eventId={}",
                            eventType, eventId);
                    return;
                }
            }

            // targetIdentifier
            String targetIdentifier = null;
            if (root.hasNonNull("targetIdentifier")) {
                targetIdentifier = root.get("targetIdentifier").asText();
                if (!targetIdentifier.matches("^(account:[0-9a-fA-F-]{36}|tombstone:[0-9a-f]{64})$")) {
                    LOGGER.warn("Audit message has invalid targetIdentifier shape, ignoring: eventType={}, eventId={}",
                            eventType, eventId);
                    return;
                }
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
