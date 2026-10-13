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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@ExtendWith(OutputCaptureExtension.class)
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
        org.mockito.Mockito.lenient().when(auditRepository.insertIfAbsent(any(SecurityAuditEventEntity.class))).thenReturn(1);
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
        when(accountRepository.existsByIdAndStatusNot(actorId, AccountStatus.DELETED)).thenReturn(true);
        when(accountRepository.existsByIdAndStatusNot(targetAccountId, AccountStatus.DELETED)).thenReturn(true);

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
        verify(auditRepository).insertIfAbsent(captor.capture());

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
    void softDeletedActorAndTargetArePersistedAsTombstones() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        var command = new AdministrationAuditIngestionService.IngestionCommand(
                UUID.randomUUID(), "identity.account.disabled", Instant.now(),
                AdministrationAuditService.AuditSourceService.IDENTITY,
                AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION,
                actorId, "ADMIN", "ACCOUNT_DISABLED", "SUCCEEDED", null,
                UUID.randomUUID(), targetId, "account:" + targetId);

        assertThat(ingestionService.ingest(command)).isTrue();
        ArgumentCaptor<SecurityAuditEventEntity> captor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
        verify(auditRepository).insertIfAbsent(captor.capture());
        assertThat(captor.getValue().getActorId()).isNull();
        assertThat(captor.getValue().getAccountId()).isNull();
        assertThat(captor.getValue().getActorReferenceHash())
                .isEqualTo(AdministrationAuditIngestionService.sha256Hex(actorId.toString()));
        assertThat(captor.getValue().getSubjectReferenceHash())
                .isEqualTo(AdministrationAuditIngestionService.sha256Hex(targetId.toString()));
    }

    @Test
    void invalidTargetIdentifierDoesNotLeakIntoConsumerOrIngestionLogs(CapturedOutput output) {
        String secret = "private-contact@example.com";
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String json = """
                {"eventId":"%s","eventType":"identity.account.disabled","occurredAt":"%s",
                 "sourceService":"IDENTITY","domain":"ACCOUNT_ADMINISTRATION","actorType":"SYSTEM",
                 "action":"ACCOUNT_DISABLED","result":"SUCCEEDED","correlationId":"%s",
                 "targetIdentifier":"%s"}
                """.formatted(eventId, Instant.now(), correlationId, secret);
        consumer.onMessage(json);
        var command = new AdministrationAuditIngestionService.IngestionCommand(
                UUID.randomUUID(), "identity.account.disabled", Instant.now(),
                AdministrationAuditService.AuditSourceService.IDENTITY,
                AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION,
                null, "SYSTEM", "ACCOUNT_DISABLED", "SUCCEEDED", null,
                UUID.randomUUID(), null, secret);
        assertThat(ingestionService.ingest(command)).isFalse();
        assertThat(output.getAll()).contains("INVALID_TARGET_SHAPE").doesNotContain(secret);
        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Security: tuple mismatch between eventType and sourceService/domain/action is rejected")
    void rejectTupleMismatchBetweenEventTypeAndDeclaredFields() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);

        // Claiming consultation.specialist.suspended but forging CONTENT / RESOURCE_MANAGEMENT / RESOURCE_ARCHIVED
        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "consultation.specialist.suspended",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.CONTENT,
                        AdministrationAuditService.AuditDomain.RESOURCE_MANAGEMENT,
                        null,
                        "SYSTEM",
                        "RESOURCE_ARCHIVED",
                        "SUCCEEDED",
                        null,
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isFalse();
        verify(auditRepository, never()).insertIfAbsent(any());
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
        verify(auditRepository, never()).insertIfAbsent(any());
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
        verify(auditRepository, never()).insertIfAbsent(any());

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
        verify(auditRepository, never()).insertIfAbsent(any());
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
        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Idempotency: concurrent duplicate race condition caught by DataIntegrityViolationException returns false")
    void concurrentDuplicateCaughtByDataIntegrityViolationException() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);
        when(auditRepository.insertIfAbsent(any())).thenThrow(
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"security_audit_event_pkey\""));

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
    @DisplayName("Idempotency: insertIfAbsent returning 0 (ON CONFLICT DO NOTHING) is treated as duplicate no-op")
    void insertIfAbsentZeroReturnsFalse() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);
        when(auditRepository.insertIfAbsent(any())).thenReturn(0);

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
    @DisplayName("Exception classification: non-duplicate check constraint violation propagates out of ingestion and consumer")
    void nonDuplicateCheckConstraintViolationPropagates() {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);
        DataIntegrityViolationException checkViolation =
                new DataIntegrityViolationException("check constraint ck_security_audit_outcome violated");
        when(auditRepository.insertIfAbsent(any())).thenThrow(checkViolation);

        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.approved",
                  "occurredAt": "%s",
                  "producer": "consultation-service",
                  "schemaVersion": "1.0",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "actorType": "SYSTEM",
                  "action": "SPECIALIST_APPROVED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(eventId, Instant.now(), correlationId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onMessage(json))
                .isSameAs(checkViolation);
    }

    @Test
    @DisplayName("Exception classification: non-duplicate foreign key violation propagates out of ingestion and consumer")
    void nonDuplicateForeignKeyViolationPropagates() {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);
        DataIntegrityViolationException fkViolation =
                new DataIntegrityViolationException("foreign key constraint fk_security_audit_account violated");
        when(auditRepository.insertIfAbsent(any())).thenThrow(fkViolation);

        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.approved",
                  "occurredAt": "%s",
                  "producer": "consultation-service",
                  "schemaVersion": "1.0",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "actorType": "SYSTEM",
                  "action": "SPECIALIST_APPROVED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(eventId, Instant.now(), correlationId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onMessage(json))
                .isSameAs(fkViolation);
    }

    @Test
    @DisplayName("Privacy: unallowlisted reasonCode is sanitized to null")
    void sanitizeReasonCodeFailClosed() {
        UUID eventId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);

        AdministrationAuditIngestionService.IngestionCommand command =
                new AdministrationAuditIngestionService.IngestionCommand(
                        eventId,
                        "content.resource.archived",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.CONTENT,
                        AdministrationAuditService.AuditDomain.RESOURCE_MANAGEMENT,
                        null,
                        "SYSTEM",
                        "RESOURCE_ARCHIVED",
                        "SUCCEEDED",
                        "ACCESS_TOKEN_SECRET_123",
                        UUID.randomUUID(),
                        null,
                        null
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isTrue();
        ArgumentCaptor<SecurityAuditEventEntity> captor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
        verify(auditRepository).insertIfAbsent(captor.capture());

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
        verify(auditRepository, never()).insertIfAbsent(any());
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
                        "content.resource.archived",
                        Instant.now(),
                        AdministrationAuditService.AuditSourceService.CONTENT,
                        AdministrationAuditService.AuditDomain.RESOURCE_MANAGEMENT,
                        null,
                        "SYSTEM",
                        "RESOURCE_ARCHIVED",
                        "SUCCEEDED",
                        "RESOURCE_ARCHIVED",
                        UUID.randomUUID(),
                        null,
                        "tombstone:" + tombstoneHash
                );

        boolean result = ingestionService.ingest(command);

        assertThat(result).isTrue();
        ArgumentCaptor<SecurityAuditEventEntity> captor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
        verify(auditRepository).insertIfAbsent(captor.capture());

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

        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Consumer: message with conflicting tuple is rejected")
    void consumerRejectsConflictingTupleMessage() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.suspended",
                  "occurredAt": "%s",
                  "producer": "content-notification-service",
                  "schemaVersion": "1.0",
                  "sourceService": "CONTENT",
                  "domain": "RESOURCE_MANAGEMENT",
                  "actorType": "SYSTEM",
                  "action": "RESOURCE_ARCHIVED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Consumer: message with invalid sourceService enum like HACKED_SERVICE is rejected fail-closed")
    void consumerRejectsMessageWithInvalidSourceService() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "producer": "identity-service",
                  "schemaVersion": "1.0",
                  "sourceService": "HACKED_SERVICE",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "actorType": "SYSTEM",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Consumer: message with invalid domain enum like HACKED_DOMAIN is rejected fail-closed")
    void consumerRejectsMessageWithInvalidDomain() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "producer": "identity-service",
                  "schemaVersion": "1.0",
                  "sourceService": "IDENTITY",
                  "domain": "HACKED_DOMAIN",
                  "actorType": "SYSTEM",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);

        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
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
        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
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
        verify(auditRepository, never()).insertIfAbsent(any());
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
        verify(auditRepository).insertIfAbsent(captor.capture());
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

        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
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

        verify(auditRepository, never()).insertIfAbsent(any());
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
        when(accountRepository.existsByIdAndStatusNot(adminId, AccountStatus.DELETED)).thenReturn(false);

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
        verify(auditRepository).insertIfAbsent(captor.capture());

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
        verify(auditRepository).insertIfAbsent(captor.capture());

        SecurityAuditEventEntity saved = captor.getValue();
        assertThat(saved.getAccountId()).isNull();
        assertThat(saved.getSubjectReferenceHash()).isNull();
    }

    @Test
    @DisplayName("Consumer: numeric schemaVersion (e.g. 1.0) is rejected fail-closed")
    void consumerRejectsNumericSchemaVersion() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "sourceService": "IDENTITY",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "actorType": "SYSTEM",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s",
                  "schemaVersion": 1.0
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);
        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Consumer: numeric producer is rejected fail-closed")
    void consumerRejectsNumericProducer() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "sourceService": "IDENTITY",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "actorType": "SYSTEM",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s",
                  "producer": 123
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);
        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Consumer: boolean or numeric actorType is rejected fail-closed")
    void consumerRejectsNonTextualActorType() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "sourceService": "IDENTITY",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "actorType": 42,
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);
        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Consumer: object reasonCode is rejected fail-closed")
    void consumerRejectsObjectReasonCode() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "sourceService": "IDENTITY",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "actorType": "SYSTEM",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s",
                  "reasonCode": {"code": "POLICY_VIOLATION"}
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);
        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Consumer: numeric targetIdentifier is rejected fail-closed")
    void consumerRejectsNumericTargetIdentifier() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "sourceService": "IDENTITY",
                  "domain": "ACCOUNT_ADMINISTRATION",
                  "actorType": "SYSTEM",
                  "action": "ACCOUNT_DISABLED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s",
                  "targetIdentifier": 12345
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);
        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Consumer: nested payload shape is rejected fail-closed")
    void consumerRejectsNestedPayloadShape() {
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "identity.account.disabled",
                  "occurredAt": "%s",
                  "correlationId": "%s",
                  "payload": {
                    "sourceService": "IDENTITY",
                    "domain": "ACCOUNT_ADMINISTRATION",
                    "actorType": "SYSTEM",
                    "action": "ACCOUNT_DISABLED",
                    "result": "SUCCEEDED"
                  }
                }
                """.formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID());

        consumer.onMessage(json);
        verify(auditRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("Consumer: valid exact v1 event fact is accepted and persisted")
    void consumerAcceptsValidExactV1Event() {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);

        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.approved",
                  "occurredAt": "%s",
                  "producer": "consultation-service",
                  "schemaVersion": "1.0",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "actorType": "SYSTEM",
                  "action": "SPECIALIST_APPROVED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(eventId, Instant.now(), correlationId);

        consumer.onMessage(json);
        verify(auditRepository).insertIfAbsent(any(SecurityAuditEventEntity.class));
    }

    @Test
    @DisplayName("Consumer: propagates persistence/runtime exception when ingestion throws")
    void consumerPropagatesIngestionPersistenceFailure() {
        AdministrationAuditIngestionService mockIngestion = org.mockito.Mockito.mock(AdministrationAuditIngestionService.class);
        AdministrationAuditEventConsumer consumerWithMock = new AdministrationAuditEventConsumer(mockIngestion, objectMapper);

        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.approved",
                  "occurredAt": "%s",
                  "producer": "consultation-service",
                  "schemaVersion": "1.0",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "actorType": "SYSTEM",
                  "action": "SPECIALIST_APPROVED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(eventId, Instant.now(), correlationId);

        org.springframework.dao.DataAccessResourceFailureException persistenceException =
                new org.springframework.dao.DataAccessResourceFailureException("DB connection unavailable");
        when(mockIngestion.ingest(any())).thenThrow(persistenceException);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumerWithMock.onMessage(json))
                .isSameAs(persistenceException);
        verify(mockIngestion).ingest(any());
    }

    @Test
    @DisplayName("Consumer: propagates runtime exception from real ingestion service when repository save fails")
    void consumerPropagatesExceptionFromRepositoryFailure() {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        when(auditRepository.existsById(eventId)).thenReturn(false);

        org.springframework.dao.DataAccessResourceFailureException dbException =
                new org.springframework.dao.DataAccessResourceFailureException("Database unreachable");
        org.mockito.Mockito.doThrow(dbException).when(auditRepository).insertIfAbsent(any());

        String json = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.approved",
                  "occurredAt": "%s",
                  "producer": "consultation-service",
                  "schemaVersion": "1.0",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "actorType": "SYSTEM",
                  "action": "SPECIALIST_APPROVED",
                  "result": "SUCCEEDED",
                  "correlationId": "%s"
                }
                """.formatted(eventId, Instant.now(), correlationId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.onMessage(json))
                .isSameAs(dbException);
    }
}
