package com.mentalbridge.identity.account;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEventEntity, UUID>,
        JpaSpecificationExecutor<SecurityAuditEventEntity> {

    @Modifying
    @Query(value = """
            INSERT INTO security_audit_event (
                id, account_id, actor_id, action, outcome, reason_code,
                correlation_id, subject_reference_hash, source_service,
                domain, actor_type, actor_reference_hash, occurred_at, created_at
            ) VALUES (
                :id, :accountId, :actorId, :action, :outcome, :reasonCode,
                :correlationId, :subjectReferenceHash, :sourceService,
                :domain, :actorType, :actorReferenceHash, :occurredAt, :createdAt
            )
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("actorId") UUID actorId,
            @Param("action") String action,
            @Param("outcome") String outcome,
            @Param("reasonCode") String reasonCode,
            @Param("correlationId") UUID correlationId,
            @Param("subjectReferenceHash") String subjectReferenceHash,
            @Param("sourceService") String sourceService,
            @Param("domain") String domain,
            @Param("actorType") String actorType,
            @Param("actorReferenceHash") String actorReferenceHash,
            @Param("occurredAt") Instant occurredAt,
            @Param("createdAt") Instant createdAt
    );

    default int insertIfAbsent(SecurityAuditEventEntity entity) {
        return insertIfAbsent(
                entity.getId(),
                entity.getAccountId(),
                entity.getActorId(),
                entity.getAction(),
                entity.getOutcome(),
                entity.getReasonCode(),
                entity.getCorrelationId(),
                entity.getSubjectReferenceHash(),
                entity.getSourceService(),
                entity.getDomain(),
                entity.getActorType(),
                entity.getActorReferenceHash(),
                entity.getOccurredAt(),
                entity.getCreatedAt()
        );
    }
}
