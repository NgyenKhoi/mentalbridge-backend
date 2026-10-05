package com.mentalbridge.identity.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationAuditIngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdministrationAuditIngestionService.class);

    public static final Set<String> ALLOWED_ACTIONS = Set.of(
            // IDENTITY / ACCOUNT_ADMINISTRATION
            "ACCOUNT_DISABLED",
            "ACCOUNT_RESTORED",
            // CONSULTATION / SPECIALIST_REVIEW
            "SPECIALIST_APPROVED",
            "SPECIALIST_REJECTED",
            "SPECIALIST_SUSPENDED",
            "SPECIALIST_RESTORED",
            // CONTENT / RESOURCE_MANAGEMENT
            "RESOURCE_PUBLISHED",
            "RESOURCE_ARCHIVED",
            "SAFETY_DIRECTORY_REVIEWED",
            "SAFETY_DIRECTORY_DEACTIVATED",
            // COMMUNITY / COMMUNITY_MODERATION
            "MODERATION_ACTION_APPLIED",
            "MODERATION_CASE_RESOLVED",
            "COMMUNITY_POST_REMOVED",
            "COMMUNITY_USER_SUSPENDED"
    );

    public static final Set<String> ALLOWED_MESSAGE_TYPES = Set.of(
            "platform.admin.audit-event",
            "identity.account.state-changed",
            "consultation.specialist.approved",
            "consultation.specialist.rejected",
            "consultation.specialist.suspended",
            "consultation.specialist.restored",
            "content.resource.published",
            "content.resource.archived",
            "content.safety-directory.reviewed",
            "content.safety-directory.deactivated",
            "community.moderation.action-applied",
            "community.moderation.case-resolved"
    );

    private final SecurityAuditEventRepository auditRepository;
    private final AccountRepository accountRepository;

    public AdministrationAuditIngestionService(
            SecurityAuditEventRepository auditRepository,
            AccountRepository accountRepository) {
        this.auditRepository = auditRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public boolean ingest(IngestionCommand command) {
        if (command == null || command.eventId() == null) {
            LOGGER.warn("Rejecting audit ingestion with missing eventId");
            return false;
        }

        // 1. Idempotency check: if event already projected, ignore duplicate
        if (auditRepository.existsById(command.eventId())) {
            LOGGER.info("Duplicate administration audit event ignored: {}", command.eventId());
            return false;
        }

        // 2. Allowlist action validation
        String action = command.action();
        if (action == null || !ALLOWED_ACTIONS.contains(action)) {
            LOGGER.warn("Rejecting unallowlisted audit action: {}", action);
            return false;
        }

        // 3. Privacy & reasonCode fail-closed allowlist
        String reasonCode = AdministrationAuditService.safeReasonCode(command.reasonCode());

        // 4. Outcome validation
        String outcome = command.result();
        if (outcome == null || (!outcome.equals("SUCCEEDED") && !outcome.equals("DENIED") && !outcome.equals("FAILED"))) {
            outcome = "SUCCEEDED";
        }

        // 5. Source service and domain resolution
        AdministrationAuditService.AuditSourceService sourceService = command.sourceService() != null
                ? command.sourceService()
                : AdministrationAuditService.AuditSourceService.IDENTITY;
        AdministrationAuditService.AuditDomain domain = command.domain() != null
                ? command.domain()
                : AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION;

        // 6. Actor resolution (only valid accounts can be foreign keys; otherwise SYSTEM)
        UUID resolvedActorId = null;
        if (command.actorId() != null && accountRepository.existsById(command.actorId())) {
            resolvedActorId = command.actorId();
        }

        // 7. Target & Tombstone resolution
        UUID resolvedAccountId = null;
        String subjectReferenceHash = null;

        if (command.targetAccountId() != null) {
            subjectReferenceHash = sha256Hex(command.targetAccountId().toString());
            if (accountRepository.existsById(command.targetAccountId())) {
                resolvedAccountId = command.targetAccountId();
            }
        } else if (command.targetIdentifier() != null) {
            String target = command.targetIdentifier().trim();
            if (target.startsWith("account:")) {
                try {
                    UUID id = UUID.fromString(target.substring("account:".length()));
                    subjectReferenceHash = sha256Hex(id.toString());
                    if (accountRepository.existsById(id)) {
                        resolvedAccountId = id;
                    }
                } catch (IllegalArgumentException ignored) {
                }
            } else if (target.startsWith("tombstone:")) {
                String hash = target.substring("tombstone:".length());
                if (hash.matches("^[0-9a-f]{64}$")) {
                    subjectReferenceHash = hash;
                }
            }
        }

        if (subjectReferenceHash == null && resolvedAccountId != null) {
            subjectReferenceHash = sha256Hex(resolvedAccountId.toString());
        }
        if (subjectReferenceHash == null) {
            subjectReferenceHash = sha256Hex(command.eventId().toString());
        }

        UUID correlationId = command.correlationId() != null ? command.correlationId() : UUID.randomUUID();
        Instant occurredAt = command.occurredAt() != null ? command.occurredAt() : Instant.now();

        // 8. Construct entity with MINIMIZED safe metadata only
        SecurityAuditEventEntity entity = new SecurityAuditEventEntity(
                command.eventId(),
                resolvedAccountId,
                resolvedActorId,
                action,
                outcome,
                reasonCode,
                correlationId,
                subjectReferenceHash,
                sourceService.name(),
                domain.name(),
                occurredAt,
                Instant.now()
        );

        try {
            auditRepository.saveAndFlush(entity);
            LOGGER.info("Ingested administration audit event {} for {}/{}", command.eventId(), sourceService, action);
            return true;
        } catch (DataIntegrityViolationException ex) {
            LOGGER.info("Concurrent duplicate administration audit event caught: {}", command.eventId());
            return false;
        }
    }

    public static String sha256Hex(String value) {
        if (value == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record IngestionCommand(
            UUID eventId,
            Instant occurredAt,
            AdministrationAuditService.AuditSourceService sourceService,
            AdministrationAuditService.AuditDomain domain,
            UUID actorId,
            String actorType,
            String action,
            String result,
            String reasonCode,
            UUID correlationId,
            UUID targetAccountId,
            String targetIdentifier
    ) {}
}
