package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AdministrationAuditIngestionServiceTests {

    @Mock
    private SecurityAuditEventRepository auditRepository;

    @Mock
    private AccountRepository accountRepository;

    private AdministrationAuditIngestionService ingestionService;
    private AdministrationAuditEventConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ingestionService = new AdministrationAuditIngestionService(auditRepository, accountRepository);
        consumer = new AdministrationAuditEventConsumer(ingestionService, objectMapper);
    }

    @Test
    @DisplayName("Ingest valid cross-service event successfully into security_audit_event projection")
    void ingestValidCrossServiceEventSuccessfully() {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID targetAccountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.now().minusSeconds(10);

        when(auditRepository.existsById(eventId)).thenReturn(false);
        when(accountRepository.existsById(actorId)).thenReturn(true);
        when(accountRepository.existsById(targetAccountId)).thenReturn(true);

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "consultation.specialist.suspended",
                        occurredAt,
                        AdministrationAuditService.AuditSourceService.CONSULTATION,
                        AdministrationAuditService.AuditDomain.SPECIALIST_REVIEW,
                        actorId,
                        "ADMIN",
                        "SPECIALIST_SUSPENDED",
                        "SUCCEEDED",
                        "POLICY_VIOLATION",
                        correlationId,
                        targetAccountId,
                        null
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isTrue();
        ArgumentCaptor<SecurityAuditEventEntity> captor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
        verify(auditRepository).saveAndFlush(captor.capture());

        SecurityAuditEventEntity saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(eventId);
        assertThat(saved.getSourceService()).isEqualTo("CONSULTATION");
        assertThat(saved.getDomain()).isEqualTo("SPECIALIST_REVIEW");
        assertThat(saved.getAction()).isEqualTo("SPECIALIST_SUSPENDED");
        assertThat(saved.getOutcome()).isEqualTo("SUCCEEDED");
        assertThat(saved.getReasonCode()).isEqualTo("POLICY_VIOLATION");
        assertThat(saved.getActorId()).isEqualTo(actorId);
        assertThat(saved.getAccountId()).isEqualTo(targetAccountId);
        assertThat(saved.getSubjectReferenceHash()).isEqualTo(AdministrationAuditIngestionService.sha256Hex(targetAccountId.toString()));
        assertThat(saved.getCorrelationId()).isEqualTo(correlationId);
        assertThat(saved.getOccurredAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("Security: tuple mismatch between eventType and sourceService/domain/action is rejected")
    void rejectTupleMismatchBetweenEventTypeAndDeclaredFields() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);

        // Claiming consultation.specialist.suspended but forging CONTENT / RESOURCE_MANAGEMENT / RESOURCE_PUBLISHED
        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "consultation.specialist.suspended",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.CONTENT,
                        AdministrationAuditService.AuditDomain.RESOURCE_MANAGEMENT,
                        null,
                        "SYSTEM",
                        "RESOURCE_PUBLISHED",
                        "SUCCEEDED",
                        null,
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isFalse();
        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Security: tuple mismatch on action is rejected")
    void rejectTupleMismatchOnAction() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);

        // consultation.specialist.suspended descriptor requires SPECIALIST_SUSPENDED, but action sent is SPECIALIST_APPROVED
        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "consultation.specialist.suspended",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.CONSULTATION,
                        AdministrationAuditService.AuditDomain.SPECIALIST_REVIEW,
                        null,
                        "SYSTEM",
                        "SPECIALIST_APPROVED",
                        "SUCCEEDED",
                        null,
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isFalse();
        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Fail-closed: invalid result/outcome is rejected and does not default to SUCCEEDED")
    void rejectInvalidResultFailClosed() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);

        AdministrationAuditIngestionService.IngestionCommand commandWithPending =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "consultation.specialist.suspended",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.CONSULTATION,
                        AdministrationAuditService.AuditDomain.SPECIALIST_REVIEW,
                        null,
                        "SYSTEM",
                        "SPECIALIST_SUSPENDED",
                        "PENDING",
                        null,
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean resultPending = ingestionService.ingest(commandWithPending);
        assertThat(resultPending).isFalse();
        verify(auditRepository, never()).saveAndFlush(any());

        AdministrationAuditIngestionService.IngestionCommand commandWithNull =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "consultation.specialist.suspended",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.CONSULTATION,
                        AdministrationAuditService.AuditDomain.SPECIALIST_REVIEW,
                        null,
                        "SYSTEM",
                        "SPECIALIST_SUSPENDED",
                        null,
                        null,
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean resultNull = ingestionService.ingest(commandWithNull);
        assertThat(resultNull).isFalse();
        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Idempotency: duplicate event ID is ignored and returns false")
    void idempotentRetryIgnoresDuplicateEventId() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(true);

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "identity.account.disabled",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.IDENTITY,
                        AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION,
                        null,
                        "SYSTEM",
                        "ACCOUNT_DISABLED",
                        "SUCCEEDED",
                        "POLICY_VIOLATION",
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isFalse();
        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Idempotency: concurrent duplicate race condition caught by DataIntegrityViolationException returns false")
    void concurrentDuplicateCaughtByDataIntegrityViolationException() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);
        when(auditRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "identity.account.disabled",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.IDENTITY,
                        AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION,
                        null,
                        "SYSTEM",
                        "ACCOUNT_DISABLED",
                        "SUCCEEDED",
                        "POLICY_VIOLATION",
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Privacy: unallowlisted reasonCode is sanitized to null")
    void sanitizeReasonCodeFailClosed() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "content.resource.published",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.CONTENT,
                        AdministrationAuditService.AuditDomain.RESOURCE_MANAGEMENT,
                        null,
                        "SYSTEM",
                        "RESOURCE_PUBLISHED",
                        "SUCCEEDED",
                        "ACCESS_TOKEN_SECRET_123",
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isTrue();
        ArgumentCaptor<SecurityAuditEventEntity> captor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
        verify(auditRepository).saveAndFlush(captor.capture());

        assertThat(captor.getValue().getReasonCode()).isNull();
    }

    @Test
    @DisplayName("Security: unallowlisted event type is rejected and not persisted")
    void rejectUnallowlistedEventType() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "chat.message.sent",
                        Instant.now(),
                        null,
                        null,
                        null,
                        "SYSTEM",
                        "SEND_UNAUTHORIZED_CHAT",
                        "SUCCEEDED",
                        null,
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isFalse();
        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Tombstone target is preserved without leaking or failing foreign keys")
    void resolveTombstoneTargetCorrectly() {
        UUID eventId = UUID.randomUUID();
        String tombstoneHash = "a".repeat(64);
        when(auditRepository.existsById(eventId)).thenReturn(false);

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "content.resource.published",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.CONTENT,
                        AdministrationAuditService.AuditDomain.RESOURCE_MANAGEMENT,
                        null,
                        "SYSTEM",
                        "RESOURCE_PUBLISHED",
                        "SUCCEEDED",
                        "REVIEW_COMPLETED",
                        UUID.randomUUID(),
                        null,
                        "tombstone:" + tombstoneHash
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isTrue();
        ArgumentCaptor<SecurityAuditEventEntity> captor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
        verify(auditRepository).saveAndFlush(captor.capture());

        SecurityAuditEventEntity saved = captor.getValue();
        assertThat(saved.getAccountId()).isNull();
        assertThat(saved.getSubjectReferenceHash()).isEqualTo(tombstoneHash);
    }

    @Test
    @DisplayName("Consumer: unallowlisted event type is ignored and never delegated")
    void consumerIgnoresUnallowlistedEventType() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "chat.message.sent",
                  "correlationId": "%s",
                  "action": "SEND_UNAUTHORIZED_CHAT",
                  "chatBody": "Sensitive private body"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with missing eventId is safely ignored without logging raw payload")
    void consumerIgnoresMessageWithMissingEventId() {
        String json = """
                {
                  "eventType": "consultation.specialist.suspended",
                  "action": "SPECIALIST_SUSPENDED",
                  "rawJournal": "PATIENT_CONFIDENTIAL_JOURNAL"
                }
                """;

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with invalid result is rejected fail-closed")
    void consumerRejectsMessageWithInvalidResult() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.suspended",
                  "action": "SPECIALIST_SUSPENDED",
                  "result": "PENDING",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with conflicting tuple is rejected")
    void consumerRejectsConflictingTupleMessage() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.suspended",
                  "occurredAt": "%s",
                  "sourceService": "CONTENT",
                  "domain": "RESOURCE_MANAGEMENT",
                  "action": "RESOURCE_PUBLISHED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message missing eventType (even if messageType present) is rejected")
    void consumerRejectsMessageMissingEventType() {
        String json = """
                {
                  "eventId": "%s",
                  "messageType": "consultation.specialist.suspended",
                  "occurredAt": "%s",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "action": "SPECIALIST_SUSPENDED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with invalid sourceService enum like HACKED_SERVICE is rejected fail-closed")
    void consumerRejectsMessageWithInvalidSourceService() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "sourceService": "HACKED_SERVICE",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with invalid domain enum like HACKED_DOMAIN is rejected fail-closed")
    void consumerRejectsMessageWithInvalidDomain() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "sourceService": "IDENTITY",
                  "domain": "HACKED_DOMAIN",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with invalid occurredAt timestamp is rejected fail-closed")
    void consumerRejectsMessageWithInvalidOccurredAt() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "invalid-timestamp-value",
                  "sourceService": "IDENTITY",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with missing occurredAt is rejected fail-closed")
    void consumerRejectsMessageWithMissingOccurredAt() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "sourceService": "IDENTITY",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("IngestionService: command with null occurredAt is rejected")
    void ingestRejectsNullOccurredAt() {
        UUID eventId = UUID.randomUUID();

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "identity.account.disabled",
                        null,
                        AdministrationAuditService.AuditSourceService.IDENTITY,
                        AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION,
                        null,
                        "SYSTEM",
                        "ACCOUNT_DISABLED",
                        "SUCCEEDED",
                        "POLICY_VIOLATION",
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);
        assertThat(result).isFalse();
        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with unexpected top-level field is rejected fail-closed (schema additionalProperties: false)")
    void consumerRejectsMessageWithUnexpectedTopLevelField() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.suspended",
                  "occurredAt": "%s",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "actorId": "%s",
                  "actorType": "ADMIN",
                  "action": "SPECIALIST_SUSPENDED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s",
                  "rawJournal": "PATIENT_CONFIDENTIAL_JOURNAL"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with nested payload shape is rejected fail-closed")
    void consumerRejectsMessageWithNestedPayloadShape() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.suspended",
                  "correlationId": "%s",
                  "payload": {
                    "occurredAt": "%s",
                    "sourceService": "CONSULTATION",
                    "domain": "SPECIALIST_REVIEW",
                    "action": "SPECIALIST_SUSPENDED",
                    "result": "SUCCEEDED"
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with missing correlationId is rejected fail-closed")
    void consumerRejectsMessageWithMissingCorrelationId() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.suspended",
                  "occurredAt": "%s",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "actorId": "%s",
                  "actorType": "ADMIN",
                  "action": "SPECIALIST_SUSPENDED",
                  "result": "SUCCEEDED"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: message with malformed correlationId is rejected fail-closed")
    void consumerRejectsMessageWithMalformedCorrelationId() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.suspended",
                  "occurredAt": "%s",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "actorId": "%s",
                  "actorType": "ADMIN",
                  "action": "SPECIALIST_SUSPENDED",
                  "result": "SUCCEEDED",
                  "correlationId": "not-a-valid-uuid"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("IngestionService: command with null correlationId is rejected fail-closed")
    void ingestRejectsNullCorrelationId() {
        UUID eventId = UUID.randomUUID();

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "identity.account.disabled",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.IDENTITY,
                        AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION,
                        null,
                        "SYSTEM",
                        "ACCOUNT_DISABLED",
                        "SUCCEEDED",
                        "POLICY_VIOLATION",
                        null,
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);
        assertThat(result).isFalse();
        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("IngestionService: valid producer correlationId is preserved exactly, never fabricated")
    void ingestPreservesExactCorrelationId() {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        when(auditRepository.existsById(eventId)).thenReturn(false);

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "identity.account.disabled",
                        occurredAt,
                        AdministrationAuditService.AuditSourceService.IDENTITY,
                        AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION,
                        null,
                        "SYSTEM",
                        "ACCOUNT_DISABLED",
                        "SUCCEEDED",
                        "POLICY_VIOLATION",
                        correlationId,
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);
        assertThat(result).isTrue();

        ArgumentCaptor<SecurityAuditEventEntity> captor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
        verify(auditRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isEqualTo(correlationId);
    }

    @Test
    @DisplayName("Consumer: message with invalid actorType like STAFF is rejected fail-closed")
    void consumerRejectsMessageWithInvalidActorType() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.suspended",
                  "occurredAt": "%s",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "actorId": "%s",
                  "actorType": "STAFF",
                  "action": "SPECIALIST_SUSPENDED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: SYSTEM actor with non-null actorId is rejected fail-closed")
    void consumerRejectsSystemActorWithActorId() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "sourceService": "IDENTITY",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "actorId": "%s",
                  "actorType": "SYSTEM",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Consumer: ADMIN actor with null actorId is rejected fail-closed")
    void consumerRejectsAdminActorWithNullActorId() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "sourceService": "IDENTITY",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "actorType": "ADMIN",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Actor semantics: deleted admin actor preserves ADMIN actorType and actorReferenceHash")
    void deletedAdminActorPreservesAdminActorTypeAndHash() {
        UUID eventId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        when(auditRepository.existsById(eventId)).thenReturn(false);
        // Admin account does not exist in Identity account repository (e.g. deleted or external)
        when(accountRepository.existsById(adminId)).thenReturn(false);

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "consultation.specialist.suspended",
                        occurredAt,
                        AdministrationAuditService.AuditSourceService.CONSULTATION,
                        AdministrationAuditService.AuditDomain.SPECIALIST_REVIEW,
                        adminId,
                        "ADMIN",
                        "SPECIALIST_SUSPENDED",
                        "SUCCEEDED",
                        "POLICY_VIOLATION",
                        correlationId,
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);
        assertThat(result).isTrue();

        ArgumentCaptor<SecurityAuditEventEntity> captor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
        verify(auditRepository).saveAndFlush(captor.capture());

        SecurityAuditEventEntity saved = captor.getValue();
        assertThat(saved.getActorType()).isEqualTo("ADMIN");
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getActorReferenceHash()).isEqualTo(AdministrationAuditIngestionService.sha256Hex(adminId.toString()));
    }

    @Test
    @DisplayName("Target tombstone: absent target does not fabricate fake eventId tombstone")
    void absentTargetDoesNotFabricateTombstone() {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        when(auditRepository.existsById(eventId)).thenReturn(false);

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "identity.account.disabled",
                        occurredAt,
                        AdministrationAuditService.AuditSourceService.IDENTITY,
                        AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION,
                        null,
                        "SYSTEM",
                        "ACCOUNT_DISABLED",
                        "SUCCEEDED",
                        "POLICY_VIOLATION",
                        correlationId,
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);
        assertThat(result).isTrue();

        ArgumentCaptor<SecurityAuditEventEntity> captor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
        verify(auditRepository).saveAndFlush(captor.capture());

        SecurityAuditEventEntity saved = captor.getValue();
        assertThat(saved.getAccountId()).isNull();
        assertThat(saved.getSubjectReferenceHash()).isNull();
    }
}
