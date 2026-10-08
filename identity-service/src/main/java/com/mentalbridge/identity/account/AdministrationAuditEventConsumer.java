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

            // Required fields per schema v1: eventId, eventType, occurredAt, producer, schemaVersion, sourceService, domain, actorType, action, result, correlationId
            if (!root.has("eventId") || !root.get("eventId").isTextual()
                    || !root.has("eventType") || !root.get("eventType").isTextual()
                    || !root.has("occurredAt") || !root.get("occurredAt").isTextual()
                    || !root.has("producer") || !root.get("producer").isTextual()
                    || !root.has("schemaVersion") || !root.get("schemaVersion").isTextual()
                    || !root.has("sourceService") || !root.get("sourceService").isTextual()
                    || !root.has("domain") || !root.get("domain").isTextual()
                    || !root.has("actorType") || !root.get("actorType").isTextual()
                    || !root.has("action") || !root.get("action").isTextual()
                    || !root.has("result") || !root.get("result").isTextual()
                    || !root.has("correlationId") || !root.get("correlationId").isTextual()) {
                LOGGER.warn("Audit message missing or non-textual required fields, ignoring: eventType={}, correlationId={}",
                        root.path("eventType").isTextual() ? root.path("eventType").textValue() : null,
                        root.path("correlationId").isTextual() ? root.path("correlationId").textValue() : null);
                return;
            }

            // producer
            String producer = root.get("producer").textValue();
            if (producer.isBlank()) {
                LOGGER.warn("Audit message has blank producer, ignoring");
                return;
            }

            // schemaVersion (strict const "1.0")
            String schemaVersion = root.get("schemaVersion").textValue();
            if (!"1.0".equals(schemaVersion)) {
                LOGGER.warn("Audit message has unsupported schemaVersion '{}', ignoring", schemaVersion);
                return;
            }

            // correlationId
            UUID correlationId;
            try {
                correlationId = UUID.fromString(root.get("correlationId").textValue());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Audit message has invalid correlationId UUID, ignoring: eventType={}",
                        root.get("eventType").textValue());
                return;
            }

            // eventId
            UUID eventId;
            try {
                eventId = UUID.fromString(root.get("eventId").textValue());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Audit message has invalid eventId UUID, ignoring: correlationId={}", correlationId);
                return;
            }

            // eventType
            String eventType = root.get("eventType").textValue();
            if (!AdministrationAuditIngestionService.ALLOWED_EVENT_TYPES.containsKey(eventType)) {
                LOGGER.debug("Ignoring unallowlisted audit eventType: {}", eventType);
                return;
            }

            // result (fail-closed)
            String result = root.get("result").textValue();
            if (!result.equals("SUCCEEDED") && !result.equals("DENIED") && !result.equals("FAILED")) {
                LOGGER.warn("Audit message with invalid result, ignoring: eventType={}, eventId={}", eventType, eventId);
                return;
            }

            // action
            String action = root.get("action").textValue();
            if (action.isBlank() || !action.matches("^[A-Z0-9_]+$")) {
                LOGGER.warn("Audit message missing or invalid action, ignoring: eventType={}, eventId={}", eventType, eventId);
                return;
            }

            // sourceService
            String sourceServiceStr = root.get("sourceService").textValue();
            AdministrationAuditService.AuditSourceService sourceService =
                    AdministrationAuditService.safeSourceService(sourceServiceStr);
            if (sourceService == null) {
                LOGGER.warn("Audit message has invalid sourceService enum '{}', ignoring: eventType={}, eventId={}",
                        sourceServiceStr, eventType, eventId);
                return;
            }

            // domain
            String domainStr = root.get("domain").textValue();
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
                occurredAt = Instant.parse(root.get("occurredAt").textValue());
            } catch (Exception e) {
                LOGGER.warn("Audit message has invalid occurredAt timestamp, ignoring: eventType={}, eventId={}",
                        eventType, eventId);
                return;
            }

            // actorId & actorType
            String actorType = root.get("actorType").textValue();
            if (!"ADMIN".equals(actorType) && !"SYSTEM".equals(actorType)) {
                LOGGER.warn("Audit message has invalid actorType '{}', ignoring: eventType={}, eventId={}",
                        actorType, eventType, eventId);
                return;
            }

            UUID actorId = null;
            if (root.hasNonNull("actorId")) {
                if (!root.get("actorId").isTextual()) {
                    LOGGER.warn("Audit message has non-textual actorId, ignoring: eventType={}, eventId={}",
                            eventType, eventId);
                    return;
                }
                try {
                    actorId = UUID.fromString(root.get("actorId").textValue());
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Audit message has invalid actorId UUID, ignoring: eventType={}, eventId={}",
                            eventType, eventId);
                    return;
                }
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
            String reasonCode = null;
            if (root.hasNonNull("reasonCode")) {
                if (!root.get("reasonCode").isTextual()) {
                    LOGGER.warn("Audit message has non-textual reasonCode, ignoring: eventType={}, eventId={}",
                            eventType, eventId);
                    return;
                }
                reasonCode = root.get("reasonCode").textValue();
            }

            // targetAccountId
            UUID targetAccountId = null;
            if (root.hasNonNull("targetAccountId")) {
                if (!root.get("targetAccountId").isTextual()) {
                    LOGGER.warn("Audit message has non-textual targetAccountId, ignoring: eventType={}, eventId={}",
                            eventType, eventId);
                    return;
                }
                try {
                    targetAccountId = UUID.fromString(root.get("targetAccountId").textValue());
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Audit message has invalid targetAccountId UUID, ignoring: eventType={}, eventId={}",
                            eventType, eventId);
                    return;
                }
            }

            // targetIdentifier & target consistency (fail-closed)
            String targetIdentifier = null;
            if (root.hasNonNull("targetIdentifier")) {
                if (!root.get("targetIdentifier").isTextual()) {
                    LOGGER.warn("Audit message has non-textual targetIdentifier, ignoring: eventType={}, eventId={}",
                            eventType, eventId);
                    return;
                }
                targetIdentifier = root.get("targetIdentifier").textValue().trim();
                if (targetIdentifier.startsWith("account:")) {
                    String rawUuid = targetIdentifier.substring("account:".length());
                    UUID parsedUuid;
                    try {
                        parsedUuid = UUID.fromString(rawUuid);
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Audit message has malformed account UUID in targetIdentifier, ignoring: eventType={}, eventId={}, targetIdentifier={}",
                                eventType, eventId, targetIdentifier);
                        return;
                    }
                    if (targetAccountId == null || !parsedUuid.equals(targetAccountId)) {
                        LOGGER.warn("Audit message has contradictory targetAccountId and targetIdentifier, ignoring: eventType={}, eventId={}",
                                eventType, eventId);
                        return;
                    }
                } else if (targetIdentifier.startsWith("tombstone:")) {
                    String hash = targetIdentifier.substring("tombstone:".length());
                    if (!hash.matches("^[0-9a-f]{64}$")) {
                        LOGGER.warn("Audit message has invalid tombstone hash in targetIdentifier, ignoring: eventType={}, eventId={}, targetIdentifier={}",
                                eventType, eventId, targetIdentifier);
                        return;
                    }
                    if (targetAccountId != null) {
                        LOGGER.warn("Audit message has targetAccountId with tombstone targetIdentifier, ignoring: eventType={}, eventId={}",
                                eventType, eventId);
                        return;
                    }
                } else {
                    LOGGER.warn("Audit message has invalid targetIdentifier prefix, ignoring: eventType={}, eventId={}, targetIdentifier={}",
                            eventType, eventId, targetIdentifier);
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
