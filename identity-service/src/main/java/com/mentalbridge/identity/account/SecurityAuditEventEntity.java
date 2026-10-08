package com.mentalbridge.identity.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "security_audit_event")
public class SecurityAuditEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "action", nullable = false, length = 96)
    private String action;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "subject_reference_hash", length = 64)
    private String subjectReferenceHash;

    @Column(name = "source_service", nullable = false, length = 32)
    private String sourceService;

    @Column(name = "domain", nullable = false, length = 64)
    private String domain;

    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    @Column(name = "actor_reference_hash", length = 64)
    private String actorReferenceHash;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SecurityAuditEventEntity() {}

    public SecurityAuditEventEntity(
            UUID id,
            UUID accountId,
            UUID actorId,
            String action,
            String outcome,
            String reasonCode,
            UUID correlationId,
            String subjectReferenceHash,
            Instant occurredAt,
            Instant createdAt) {
        this(id, accountId, actorId, action, outcome, reasonCode, correlationId, subjectReferenceHash,
                "IDENTITY", "ACCOUNT_ADMINISTRATION",
                actorId != null ? "ADMIN" : "SYSTEM",
                actorId != null ? AdministrationAuditIngestionService.sha256Hex(actorId.toString()) : null,
                occurredAt, createdAt);
    }

    public SecurityAuditEventEntity(
            UUID id,
            UUID accountId,
            UUID actorId,
            String action,
            String outcome,
            String reasonCode,
            UUID correlationId,
            String subjectReferenceHash,
            String sourceService,
            String domain,
            Instant occurredAt,
            Instant createdAt) {
        this(id, accountId, actorId, action, outcome, reasonCode, correlationId, subjectReferenceHash,
                sourceService, domain,
                actorId != null ? "ADMIN" : "SYSTEM",
                actorId != null ? AdministrationAuditIngestionService.sha256Hex(actorId.toString()) : null,
                occurredAt, createdAt);
    }

    public SecurityAuditEventEntity(
            UUID id,
            UUID accountId,
            UUID actorId,
            String action,
            String outcome,
            String reasonCode,
            UUID correlationId,
            String subjectReferenceHash,
            String sourceService,
            String domain,
            String actorType,
            String actorReferenceHash,
            Instant occurredAt,
            Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.actorId = actorId;
        this.action = action;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.correlationId = correlationId;
        this.subjectReferenceHash = subjectReferenceHash;
        this.sourceService = sourceService != null ? sourceService : "IDENTITY";
        this.domain = domain != null ? domain : "ACCOUNT_ADMINISTRATION";
        this.actorType = actorType != null ? actorType : (actorId != null ? "ADMIN" : "SYSTEM");
        this.actorReferenceHash = actorReferenceHash;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorType() {
        return actorType;
    }

    public String getActorReferenceHash() {
        return actorReferenceHash;
    }

    public String getAction() {
        return action;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getSubjectReferenceHash() {
        return subjectReferenceHash;
    }

    public String getSourceService() {
        return sourceService;
    }

    public String getDomain() {
        return domain;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
