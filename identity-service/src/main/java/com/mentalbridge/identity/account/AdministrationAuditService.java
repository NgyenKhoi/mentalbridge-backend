package com.mentalbridge.identity.account;

import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationAuditService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    static final int MAX_EXPORT_ROWS = 5_000;
    static final int MAX_EXPORT_BYTES = 5 * 1_024 * 1_024;
    static final Duration DEFAULT_WINDOW = Duration.ofDays(30);
    static final Duration MAX_WINDOW = Duration.ofDays(90);
    static final Duration RETENTION = Duration.ofDays(365);

    private final SecurityAuditEventRepository auditEvents;
    private final Clock clock;

    public AdministrationAuditService(SecurityAuditEventRepository auditEvents, Clock clock) {
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AuditEventPage browse(AuditQuery query, String cursor, Integer requestedLimit) {
        EffectiveQuery effective = effective(query);
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidAdminAccountQueryException("Limit must be between 1 and 100");
        }
        Cursor after = cursor == null ? null : decodeCursor(cursor);
        var pageable = PageRequest.of(0, limit + 1,
                Sort.by("occurredAt").descending().and(Sort.by("id").descending()));
        var found = auditEvents.findAll(specification(effective, after), pageable).getContent();
        boolean hasNext = found.size() > limit;
        var pageItems = found.subList(0, Math.min(limit, found.size())).stream().map(this::response).toList();
        String nextCursor = hasNext ? encodeCursor(found.get(limit - 1)) : null;
        return new AuditEventPage(pageItems, nextCursor, effective.from(), effective.to(), effective.retentionCutoff());
    }

    @Transactional(readOnly = true)
    public AuditExport export(AuditQuery query) {
        EffectiveQuery effective = effective(query);
        var pageable = PageRequest.of(0, MAX_EXPORT_ROWS + 1,
                Sort.by("occurredAt").descending().and(Sort.by("id").descending()));
        var found = auditEvents.findAll(specification(effective, null), pageable).getContent();
        if (found.size() > MAX_EXPORT_ROWS) {
            throw new InvalidAdminAccountQueryException("Export exceeds the 5000 row limit");
        }

        StringBuilder csv = new StringBuilder("eventId,occurredAt,actorType,actorIdentifier,action,result,reasonCode,correlationId,sourceService,domain,targetIdentifier\r\n");
        found.stream().map(this::response).forEach(event -> csv.append(csvRow(event)));
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EXPORT_BYTES) {
            throw new InvalidAdminAccountQueryException("Export exceeds the 5 MiB limit");
        }
        return new AuditExport(bytes, found.size(), effective.from(), effective.to());
    }

    private EffectiveQuery effective(AuditQuery query) {
        Instant now = clock.instant();
        Instant retentionCutoff = now.minus(RETENTION);
        Instant to = query.to() == null ? now : query.to();
        Instant from = query.from() == null ? to.minus(DEFAULT_WINDOW) : query.from();
        if (to.isAfter(now)) {
            throw new InvalidAdminAccountQueryException("Audit end must not be in the future");
        }
        if (from.isBefore(retentionCutoff)) {
            throw new InvalidAdminAccountQueryException("Audit start precedes the retention boundary");
        }
        if (from.isAfter(to)) {
            throw new InvalidAdminAccountQueryException("Audit start must not be after end");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new InvalidAdminAccountQueryException("Audit query window must not exceed 90 days");
        }
        if (query.action() != null && (query.action().isBlank() || !query.action().matches("^[A-Z0-9_]+$"))) {
            throw new InvalidAdminAccountQueryException("Audit action is invalid");
        }
        Target target = parseTarget(query.targetIdentifier());
        return new EffectiveQuery(from, to, retentionCutoff, query.sourceService(), query.domain(), query.actorType(),
                query.action(), query.result(), target);
    }

    private Specification<SecurityAuditEventEntity> specification(EffectiveQuery query, Cursor after) {
        return (root, ignored, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), query.from()));
            predicates.add(builder.lessThanOrEqualTo(root.get("occurredAt"), query.to()));
            if (query.actorType() == AuditActorType.ADMIN) predicates.add(builder.isNotNull(root.get("actorId")));
            if (query.actorType() == AuditActorType.SYSTEM) predicates.add(builder.isNull(root.get("actorId")));
            if (query.action() != null) predicates.add(builder.equal(root.get("action"), query.action()));
            if (query.result() != null) predicates.add(builder.equal(root.get("outcome"), query.result().name()));
            if (query.target() != null && query.target().accountId() != null) {
                predicates.add(builder.equal(root.get("accountId"), query.target().accountId()));
            }
            if (query.target() != null && query.target().hash() != null) {
                predicates.add(builder.equal(root.get("subjectReferenceHash"), query.target().hash()));
            }
            if (after != null) {
                predicates.add(builder.or(
                        builder.lessThan(root.<Instant>get("occurredAt"), after.occurredAt()),
                        builder.and(builder.equal(root.<Instant>get("occurredAt"), after.occurredAt()),
                                builder.lessThan(root.<UUID>get("id"), after.eventId()))));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Target parseTarget(String identifier) {
        if (identifier == null) return null;
        try {
            if (identifier.startsWith("account:")) {
                return new Target(UUID.fromString(identifier.substring("account:".length())), null);
            }
        }
        catch (IllegalArgumentException exception) {
            throw new InvalidAdminAccountQueryException("Audit target identifier is invalid");
        }
        if (identifier.matches("^tombstone:[0-9a-f]{64}$")) {
            return new Target(null, identifier.substring("tombstone:".length()));
        }
        throw new InvalidAdminAccountQueryException("Audit target identifier is invalid");
    }

    private AuditEvent response(SecurityAuditEventEntity event) {
        String actorIdentifier = event.getActorId() == null ? "system" : "account:" + event.getActorId();
        AuditActorType actorType = event.getActorId() == null ? AuditActorType.SYSTEM : AuditActorType.ADMIN;
        String targetIdentifier = event.getAccountId() == null
                ? "tombstone:" + event.getSubjectReferenceHash()
                : "account:" + event.getAccountId();
        return new AuditEvent(event.getId(), event.getOccurredAt(), actorType, actorIdentifier,
                safeCode(event.getAction(), "UNKNOWN_EVENT"), AuditResult.valueOf(event.getOutcome()),
                safeCode(event.getReasonCode(), null), event.getCorrelationId(),
                AuditSourceService.IDENTITY, AuditDomain.ACCOUNT_ADMINISTRATION, targetIdentifier);
    }

    private String safeCode(String value, String fallback) {
        return value != null && value.length() <= 96 && value.matches("^[A-Z0-9_]+$") ? value : fallback;
    }

    private String csvRow(AuditEvent event) {
        return List.of(event.eventId().toString(), event.occurredAt().toString(), event.actorType().name(),
                event.actorIdentifier(), event.action(), event.result().name(), nullToEmpty(event.reasonCode()),
                event.correlationId().toString(), event.sourceService().name(), event.domain().name(),
                event.targetIdentifier()).stream().map(this::csvCell).reduce((left, right) -> left + "," + right)
                .orElse("") + "\r\n";
    }

    private String csvCell(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String encodeCursor(SecurityAuditEventEntity event) {
        String raw = event.getOccurredAt() + "|" + event.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String encoded) {
        if (encoded.isBlank() || encoded.length() > 512) {
            throw new InvalidAdminAccountQueryException("Audit cursor is invalid");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        }
        catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new InvalidAdminAccountQueryException("Audit cursor is invalid");
        }
    }

    public enum AuditSourceService { IDENTITY }
    public enum AuditDomain { ACCOUNT_ADMINISTRATION }
    public enum AuditActorType { ADMIN, SYSTEM }
    public enum AuditResult { SUCCEEDED, DENIED, FAILED }

    public record AuditQuery(Instant from, Instant to, AuditSourceService sourceService, AuditDomain domain,
            AuditActorType actorType, String action, AuditResult result, String targetIdentifier) {}

    public record AuditEvent(UUID eventId, Instant occurredAt, AuditActorType actorType, String actorIdentifier,
            String action, AuditResult result, String reasonCode, UUID correlationId,
            AuditSourceService sourceService, AuditDomain domain, String targetIdentifier) {}

    public record AuditEventPage(List<AuditEvent> items, String nextCursor, Instant effectiveFrom, Instant effectiveTo,
            Instant retentionCutoff) {
        public AuditEventPage { items = List.copyOf(items); }
    }

    public record AuditExport(byte[] bytes, int rowCount, Instant effectiveFrom, Instant effectiveTo) {}
    private record EffectiveQuery(Instant from, Instant to, Instant retentionCutoff,
            AuditSourceService sourceService, AuditDomain domain, AuditActorType actorType, String action,
            AuditResult result, Target target) {}
    private record Target(UUID accountId, String hash) {}
    private record Cursor(Instant occurredAt, UUID eventId) {}
}
