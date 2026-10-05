package com.mentalbridge.identity.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationAuditIngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdministrationAuditIngestionService.class);

    public record EventTypeDescriptor(
            AdministrationAuditService.AuditSourceService sourceService,
            AdministrationAuditService.AuditDomain domain,
            String action
    ) {}

    public static final Map<String, EventTypeDescriptor> ALLOWED_EVENT_TYPES = Map.ofEntries(
            // IDENTITY / ACCOUNT_ADMINISTRATION
            Map.entry("identity.account.disabled",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.IDENTITY,
                            AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION,
                            "ACCOUNT_DISABLED")),
            Map.entry("identity.account.restored",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.IDENTITY,
                            AdministrationAuditService.AuditDomain.ACCOUNT_ADMINISTRATION,
                            "ACCOUNT_RESTORED")),

            // CONSULTATION / SPECIALIST_REVIEW
            Map.entry("consultation.specialist.approved",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.CONSULTATION,
                            AdministrationAuditService.AuditDomain.SPECIALIST_REVIEW,
                            "SPECIALIST_APPROVED")),
            Map.entry("consultation.specialist.rejected",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.CONSULTATION,
                            AdministrationAuditService.AuditDomain.SPECIALIST_REVIEW,
                            "SPECIALIST_REJECTED")),
            Map.entry("consultation.specialist.suspended",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.CONSULTATION,
                            AdministrationAuditService.AuditDomain.SPECIALIST_REVIEW,
                            "SPECIALIST_SUSPENDED")),
            Map.entry("consultation.specialist.restored",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.CONSULTATION,
                            AdministrationAuditService.AuditDomain.SPECIALIST_REVIEW,
                            "SPECIALIST_RESTORED")),

            // CONTENT / RESOURCE_MANAGEMENT
            Map.entry("content.resource.published",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.CONTENT,
                            AdministrationAuditService.AuditDomain.RESOURCE_MANAGEMENT,
                            "RESOURCE_PUBLISHED")),
            Map.entry("content.resource.archived",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.CONTENT,
                            AdministrationAuditService.AuditDomain.RESOURCE_MANAGEMENT,
                            "RESOURCE_ARCHIVED")),
            Map.entry("content.safety-directory.reviewed",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.CONTENT,
                            AdministrationAuditService.AuditDomain.RESOURCE_MANAGEMENT,
                            "SAFETY_DIRECTORY_REVIEWED")),
            Map.entry("content.safety-directory.deactivated",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.CONTENT,
                            AdministrationAuditService.AuditDomain.RESOURCE_MANAGEMENT,
                            "SAFETY_DIRECTORY_DEACTIVATED")),

            // COMMUNITY / COMMUNITY_MODERATION
            Map.entry("community.moderation.action-applied",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.COMMUNITY,
                            AdministrationAuditService.AuditDomain.COMMUNITY_MODERATION,
                            "MODERATION_ACTION_APPLIED")),
            Map.entry("community.moderation.case-resolved",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.COMMUNITY,
                            AdministrationAuditService.AuditDomain.COMMUNITY_MODERATION,
                            "MODERATION_CASE_RESOLVED")),
            Map.entry("community.post.removed",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.COMMUNITY,
                            AdministrationAuditService.AuditDomain.COMMUNITY_MODERATION,
                            "COMMUNITY_POST_REMOVED")),
            Map.entry("community.user.suspended",
                    new EventTypeDescriptor(
                            AdministrationAuditService.AuditSourceService.COMMUNITY,
                            AdministrationAuditService.AuditDomain.COMMUNITY_MODERATION,
                            "COMMUNITY_USER_SUSPENDED"))
    );

    public static final Set<String> ALLOWED_ACTIONS = ALLOWED_EVENT_TYPES.values().stream()
            .map(EventTypeDescriptor::action)
            .collect(Collectors.toUnmodifiableSet());

    public static final Set<String> ALLOWED_MESSAGE_TYPES = ALLOWED_EVENT_TYPES.keySet();

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

        if (command.occurredAt() == null) {
            LOGGER.warn("Rejecting audit ingestion with missing occurredAt: eventId={}", command.eventId());
            return false;
        }

        // 1. Idempotency check: if event already projected, ignore duplicate
        if (auditRepository.existsById(command.eventId())) {
            LOGGER.info("Duplicate administration audit event ignored: {}", command.eventId());
            return false;
        }

        // 2. Strict descriptor lookup & tuple binding
        EventTypeDescriptor descriptor = null;
        if (command.eventType() != null) {
            if (!ALLOWED_EVENT_TYPES.containsKey(command.eventType())) {
                LOGGER.warn("Rejecting unallowlisted audit eventType: {}", command.eventType());
                return false;
            }
            descriptor = ALLOWED_EVENT_TYPES.get(command.eventType());
        } else {
            for (var entry : ALLOWED_EVENT_TYPES.entrySet()) {
                var d = entry.getValue();
                if ((command.sourceService() == null || d.sourceService() == command.sourceService())
                        && (command.domain() == null || d.domain() == command.domain())
                        && (command.action() != null && d.action().equals(command.action()))) {
                    descriptor = d;
                    break;
                }
            }
            if (descriptor == null) {
                LOGGER.warn("Rejecting audit event with unknown event type or action tuple: action={}", command.action());
                return false;
            }
        }

        // Strict cross-service tuple binding validation (fail-closed)
        if (command.sourceService() == null || command.sourceService() != descriptor.sourceService()) {
            LOGGER.warn("Rejecting audit event {} due to sourceService mismatch for {}: expected {}, got {}",
                    command.eventId(), command.eventType(), descriptor.sourceService(), command.sourceService());
            return false;
        }
        if (command.domain() == null || command.domain() != descriptor.domain()) {
            LOGGER.warn("Rejecting audit event {} due to domain mismatch for {}: expected {}, got {}",
                    command.eventId(), command.eventType(), descriptor.domain(), command.domain());
            return false;
        }
        if (command.action() == null || !command.action().equals(descriptor.action())) {
            LOGGER.warn("Rejecting audit event {} due to action mismatch for {}: expected {}, got {}",
                    command.eventId(), command.eventType(), descriptor.action(), command.action());
            return false;
        }

        AdministrationAuditService.AuditSourceService sourceService = descriptor.sourceService();
        AdministrationAuditService.AuditDomain domain = descriptor.domain();
        String action = descriptor.action();

        // 3. Outcome validation (fail-closed)
        String outcome = command.result();
        if (outcome == null || (!outcome.equals("SUCCEEDED") && !outcome.equals("DENIED") && !outcome.equals("FAILED"))) {
            LOGGER.warn("Rejecting audit event {} with invalid outcome/result: {}", command.eventId(), outcome);
            return false;
        }

        // 4. Privacy & reasonCode fail-closed allowlist
        String reasonCode = AdministrationAuditService.safeReasonCode(command.reasonCode());

        // 5. Actor resolution (only valid accounts can be foreign keys; otherwise SYSTEM)
        UUID resolvedActorId = null;
        if (command.actorId() != null && accountRepository.existsById(command.actorId())) {
            resolvedActorId = command.actorId();
        }

        // 6. Target & Tombstone resolution
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
        Instant occurredAt = command.occurredAt();

        // 7. Construct entity with MINIMIZED safe metadata only
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
            String eventType,
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
    ) {
        public IngestionCommand(
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
        ) {
            this(eventId, null, occurredAt, sourceService, domain, actorId, actorType, action, result, reasonCode, correlationId, targetAccountId, targetIdentifier);
        }
    }
}
