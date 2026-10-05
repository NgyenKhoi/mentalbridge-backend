package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mentalbridge.identity.account.AdministrationAuditService.AuditQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

class AdministrationAuditServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private SecurityAuditEventRepository repository;
    private AdministrationAuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(SecurityAuditEventRepository.class);
        service = new AdministrationAuditService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void browsePaginatesAndSanitizesPersistedFreeText() {
        UUID actorId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        var first = event(accountId, actorId, "ACCOUNT_DISABLED", "DENIED", "provider-secret-payload",
                NOW.minusSeconds(60));
        var second = event(accountId, actorId, "ACCOUNT_RESTORED", "SUCCEEDED", "REVIEW_COMPLETED",
                NOW.minusSeconds(120));
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second)));

        var page = service.browse(new AuditQuery(null, null, null, null, null, null, null, null), null, 1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(page.items().getFirst().reasonCode()).isNull();
        assertThat(page.items().getFirst().actorIdentifier()).isEqualTo("account:" + actorId);
        assertThat(page.items().getFirst().targetIdentifier()).isEqualTo("account:" + accountId);
        assertThat(page.effectiveFrom()).isEqualTo(NOW.minus(AdministrationAuditService.DEFAULT_WINDOW));
    }

    @Test
    void deletedSubjectUsesOnlyTheRetainedTombstoneHash() {
        String hash = "a".repeat(64);
        var event = new SecurityAuditEventEntity(UUID.randomUUID(), null, null, "ACCOUNT_DISABLED", "FAILED",
                "POLICY_VIOLATION", UUID.randomUUID(), hash, NOW.minusSeconds(10), NOW.minusSeconds(10));
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event)));

        var page = service.browse(new AuditQuery(null, null, null, null, null, null, null, null), null, 10);

        assertThat(page.items().getFirst().targetIdentifier()).isEqualTo("tombstone:" + hash);
        assertThat(page.items().getFirst().actorType()).isEqualTo(AdministrationAuditService.AuditActorType.SYSTEM);
    }

    @Test
    void exportUsesSafeColumnsAndRejectsMoreThanTheRowLimit() {
        UUID accountId = UUID.randomUUID();
        var safe = event(accountId, UUID.randomUUID(), "ACCOUNT_DISABLED", "SUCCEEDED", "SAFETY_CONCERN",
                NOW.minusSeconds(60));
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(safe)));

        String csv = new String(service.export(new AuditQuery(null, null, null, null, null, null, null, null)).bytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("eventId,occurredAt,actorType", "ACCOUNT_DISABLED", "account:" + accountId)
                .doesNotContain("email", "password", "journal", "provider payload");

        var oversized = IntStream.range(0, AdministrationAuditService.MAX_EXPORT_ROWS + 1)
                .mapToObj(index -> event(accountId, UUID.randomUUID(), "ACCOUNT_DISABLED", "SUCCEEDED",
                        "SAFETY_CONCERN", NOW.minusSeconds(index + 1L)))
                .toList();
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(oversized));
        assertThatThrownBy(() -> service.export(new AuditQuery(null, null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidAdminAccountQueryException.class)
                .hasMessageContaining("5000 row limit");
    }

    @Test
    void invalidRangeRetentionTargetAndCursorFailBeforeQuerying() {
        assertThatThrownBy(() -> service.browse(
                new AuditQuery(NOW.minusSeconds(60), NOW.minusSeconds(120), null, null, null, null, null, null),
                null, 10)).isInstanceOf(InvalidAdminAccountQueryException.class);
        assertThatThrownBy(() -> service.browse(
                new AuditQuery(NOW.minus(AdministrationAuditService.RETENTION).minusSeconds(1), NOW, null, null,
                        null, null, null, null), null, 10))
                .isInstanceOf(InvalidAdminAccountQueryException.class);
        assertThatThrownBy(() -> service.browse(
                new AuditQuery(null, null, null, null, null, null, null, "account:not-a-uuid"), null, 10))
                .isInstanceOf(InvalidAdminAccountQueryException.class);
        assertThatThrownBy(() -> service.browse(
                new AuditQuery(null, null, null, null, null, null, null, null), "not-a-cursor", 10))
                .isInstanceOf(InvalidAdminAccountQueryException.class);
    }

    private SecurityAuditEventEntity event(UUID accountId, UUID actorId, String action, String outcome, String reason,
            Instant occurredAt) {
        return new SecurityAuditEventEntity(UUID.randomUUID(), accountId, actorId, action, outcome, reason,
                UUID.randomUUID(), "b".repeat(64), occurredAt, occurredAt);
    }
}
