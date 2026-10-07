package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.mentalbridge.identity.authentication.AuthenticationPersistence;
import com.mentalbridge.identity.registration.OutboxEventEntity;
import com.mentalbridge.identity.registration.OutboxEventRepository;

class AdminAccountServiceTests {

	private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

	private AccountRepository accounts;
	private AuthenticationPersistence authentication;
	private SecurityAuditEventRepository audits;
	private OutboxEventRepository outbox;
	private AdminAccountService service;

	@BeforeEach
	void setUp() {
		accounts = mock(AccountRepository.class);
		authentication = mock(AuthenticationPersistence.class);
		audits = mock(SecurityAuditEventRepository.class);
		outbox = mock(OutboxEventRepository.class);
		service = new AdminAccountService(accounts, authentication, audits, outbox,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void listsBoundedSafeAccountFacts() {
		AccountEntity account = active(RoleCode.USER);
		when(accounts.findAll(any(Specification.class), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(account)));

		AdminAccountService.AccountPage page = service.searchAccounts(AccountStatus.ACTIVE, RoleCode.USER,
				" USER@example.com ", null, 20);

		assertThat(page.items()).singleElement().satisfies(item -> {
			assertThat(item.accountId()).isEqualTo(account.id());
			assertThat(item.email()).isEqualTo("user@example.com");
			assertThat(item.roles()).containsExactly(RoleCode.USER);
		});
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void rejectsOutOfBoundsLimitAndMalformedCursor() {
		assertThatThrownBy(() -> service.searchAccounts(null, null, null, null, 101))
				.isInstanceOf(InvalidAdminAccountQueryException.class);
		assertThatThrownBy(() -> service.searchAccounts(null, null, null, "not-a-cursor", 20))
				.isInstanceOf(InvalidAdminAccountQueryException.class);
	}

	@Test
	void suspendsUserRevokesSessionsAndWritesAuditAndOutbox() {
		AccountEntity account = active(RoleCode.USER);
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		when(accounts.findByIdForUpdate(account.id())).thenReturn(Optional.of(account));

		var result = service.changeAccountState(actorId, account.id(),
				new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.SAFETY_CONCERN),
				account.version(), correlationId);

		assertThat(result.status()).isEqualTo(AccountStatus.DISABLED);
		verify(authentication).revokeAll(account.id(), "ACCOUNT_DISABLED", NOW);
		var audit = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
		verify(audits).save(audit.capture());
		assertThat(audit.getValue().getActorId()).isEqualTo(actorId);
		assertThat(audit.getValue().getReasonCode()).isEqualTo("SAFETY_CONCERN");
		verify(outbox).save(any(OutboxEventEntity.class));
	}

	@Test
	void suspendsSpecialistThroughTheSameIdentityLifecycle() {
		AccountEntity account = active(RoleCode.SPECIALIST);
		when(accounts.findByIdForUpdate(account.id())).thenReturn(Optional.of(account));

		var result = service.changeAccountState(UUID.randomUUID(), account.id(),
				new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.ACCOUNT_REVIEW_REQUIRED),
				account.version(), UUID.randomUUID());

		assertThat(result.roles()).containsExactly(RoleCode.SPECIALIST);
		assertThat(result.status()).isEqualTo(AccountStatus.DISABLED);
		verify(outbox).save(any(OutboxEventEntity.class));
	}

	@Test
	void repeatedSuspendIsANoOpWithoutDuplicateSideEffects() {
		AccountEntity account = active(RoleCode.USER);
		account.disable(NOW.minusSeconds(60));
		when(accounts.findByIdForUpdate(account.id())).thenReturn(Optional.of(account));

		var result = service.changeAccountState(UUID.randomUUID(), account.id(),
				new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.POLICY_VIOLATION),
				account.version(), UUID.randomUUID());

		assertThat(result.status()).isEqualTo(AccountStatus.DISABLED);
		verify(authentication, never()).revokeAll(any(), any(), any());
		verify(audits, never()).save(any());
		verify(outbox, never()).save(any());
		verify(accounts, never()).saveAndFlush(any());
	}

	@Test
	void restoresDisabledAccountAndRepeatedRestoreIsANoOp() {
		AccountEntity account = active(RoleCode.USER);
		account.disable(NOW.minusSeconds(60));
		when(accounts.findByIdForUpdate(account.id())).thenReturn(Optional.of(account));

		var restored = service.changeAccountState(UUID.randomUUID(), account.id(),
				new AccountStateChangeRequest(AccountStatus.ACTIVE, AccountStateReasonCode.REVIEW_COMPLETED),
				account.version(), UUID.randomUUID());
		assertThat(restored.status()).isEqualTo(AccountStatus.ACTIVE);
		verify(audits).save(any());
		verify(outbox).save(any());

		service.changeAccountState(UUID.randomUUID(), account.id(),
				new AccountStateChangeRequest(AccountStatus.ACTIVE, AccountStateReasonCode.REVIEW_COMPLETED),
				account.version(), UUID.randomUUID());
		verify(audits).save(any());
		verify(outbox).save(any());
	}

	@Test
	void dedicatedAdminMutationWritesDeniedAuditDoesNotMutateAndDoesNotEmitOutbox() {
		AccountEntity admin = active(RoleCode.ADMIN);
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		when(accounts.findByIdForUpdate(admin.id())).thenReturn(Optional.of(admin));

		AccountStateChangeRequest request = new AccountStateChangeRequest(AccountStatus.DISABLED,
				AccountStateReasonCode.POLICY_VIOLATION);

		assertThatThrownBy(() -> service.changeAccountState(actorId, admin.id(), request, admin.version(),
				correlationId)).isInstanceOf(DedicatedAdminProtectionException.class);

		var auditCaptor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
		verify(audits).save(auditCaptor.capture());
		SecurityAuditEventEntity audit = auditCaptor.getValue();
		assertThat(audit.getActorId()).isEqualTo(actorId);
		assertThat(audit.getAccountId()).isEqualTo(admin.id());
		assertThat(audit.getAction()).isEqualTo("ACCOUNT_DISABLED");
		assertThat(audit.getOutcome()).isEqualTo("DENIED");
		assertThat(audit.getReasonCode()).isEqualTo("POLICY_VIOLATION");
		assertThat(audit.getCorrelationId()).isEqualTo(correlationId);
		assertThat(audit.getOccurredAt()).isEqualTo(NOW);
		assertThat(audit.getSubjectReferenceHash()).isNull();

		assertThat(admin.status()).isEqualTo(AccountStatus.ACTIVE);
		assertThat(admin.version()).isEqualTo(0L);
		verify(outbox, never()).save(any());
		verify(accounts, never()).saveAndFlush(any());
		verify(authentication, never()).revokeAll(any(), any(), any());
	}

	@Test
	void specialistSuspendAndRestoreLifecycleWithIdempotence() {
		AccountEntity specialist = active(RoleCode.SPECIALIST);
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		when(accounts.findByIdForUpdate(specialist.id())).thenReturn(Optional.of(specialist));

		// 1. Suspend SPECIALIST
		var suspended = service.changeAccountState(actorId, specialist.id(),
				new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.ACCOUNT_REVIEW_REQUIRED),
				specialist.version(), correlationId);

		assertThat(suspended.status()).isEqualTo(AccountStatus.DISABLED);
		assertThat(suspended.roles()).containsExactly(RoleCode.SPECIALIST);
		verify(authentication).revokeAll(specialist.id(), "ACCOUNT_DISABLED", NOW);

		var auditCaptor = ArgumentCaptor.forClass(SecurityAuditEventEntity.class);
		verify(audits).save(auditCaptor.capture());
		assertThat(auditCaptor.getValue().getOutcome()).isEqualTo("SUCCEEDED");
		assertThat(auditCaptor.getValue().getAction()).isEqualTo("ACCOUNT_DISABLED");
		assertThat(auditCaptor.getValue().getReasonCode()).isEqualTo("ACCOUNT_REVIEW_REQUIRED");
		assertThat(auditCaptor.getValue().getCorrelationId()).isEqualTo(correlationId);

		var outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
		verify(outbox).save(outboxCaptor.capture());
		assertThat(outboxCaptor.getValue().messageType()).isEqualTo("identity.account.state-changed");
		assertThat(outboxCaptor.getValue().aggregateId()).isEqualTo(specialist.id());
		assertThat(outboxCaptor.getValue().correlationId()).isEqualTo(correlationId);
		assertThat(outboxCaptor.getValue().payload())
				.containsEntry("accountId", specialist.id().toString())
				.containsEntry("status", "DISABLED")
				.containsEntry("role", "SPECIALIST")
				.containsEntry("reasonCode", "ACCOUNT_REVIEW_REQUIRED");

		// 2. Repeated suspend SPECIALIST (idempotent no-op)
		var repeatSuspended = service.changeAccountState(actorId, specialist.id(),
				new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.ACCOUNT_REVIEW_REQUIRED),
				specialist.version(), UUID.randomUUID());
		assertThat(repeatSuspended.status()).isEqualTo(AccountStatus.DISABLED);
		verify(audits, org.mockito.Mockito.times(1)).save(any());
		verify(outbox, org.mockito.Mockito.times(1)).save(any());

		// 3. Restore SPECIALIST
		UUID restoreCorrelationId = UUID.randomUUID();
		var restored = service.changeAccountState(actorId, specialist.id(),
				new AccountStateChangeRequest(AccountStatus.ACTIVE, AccountStateReasonCode.REVIEW_COMPLETED),
				specialist.version(), restoreCorrelationId);

		assertThat(restored.status()).isEqualTo(AccountStatus.ACTIVE);
		assertThat(restored.roles()).containsExactly(RoleCode.SPECIALIST);
		verify(audits, org.mockito.Mockito.times(2)).save(auditCaptor.capture());
		assertThat(auditCaptor.getValue().getOutcome()).isEqualTo("SUCCEEDED");
		assertThat(auditCaptor.getValue().getAction()).isEqualTo("ACCOUNT_RESTORED");
		assertThat(auditCaptor.getValue().getReasonCode()).isEqualTo("REVIEW_COMPLETED");
		assertThat(auditCaptor.getValue().getCorrelationId()).isEqualTo(restoreCorrelationId);

		verify(outbox, org.mockito.Mockito.times(2)).save(outboxCaptor.capture());
		assertThat(outboxCaptor.getValue().payload())
				.containsEntry("status", "ACTIVE")
				.containsEntry("role", "SPECIALIST")
				.containsEntry("reasonCode", "REVIEW_COMPLETED");

		// 4. Repeated restore SPECIALIST (idempotent no-op)
		var repeatRestored = service.changeAccountState(actorId, specialist.id(),
				new AccountStateChangeRequest(AccountStatus.ACTIVE, AccountStateReasonCode.REVIEW_COMPLETED),
				specialist.version(), UUID.randomUUID());
		assertThat(repeatRestored.status()).isEqualTo(AccountStatus.ACTIVE);
		verify(audits, org.mockito.Mockito.times(2)).save(any());
		verify(outbox, org.mockito.Mockito.times(2)).save(any());
	}

	@Test
	void rejectsStaleVersionAndInvalidReason() {
		AccountEntity user = active(RoleCode.USER);
		when(accounts.findByIdForUpdate(user.id())).thenReturn(Optional.of(user));
		assertThatThrownBy(() -> service.changeAccountState(UUID.randomUUID(), user.id(),
				new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.SAFETY_CONCERN),
				user.version() + 1, UUID.randomUUID())).isInstanceOf(AccountVersionMismatchException.class);

		assertThatThrownBy(() -> service.changeAccountState(UUID.randomUUID(), user.id(),
				new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.REVIEW_COMPLETED),
				user.version(), UUID.randomUUID())).isInstanceOf(InvalidStateTransitionException.class);
	}

	@Test
	void getAccountsSummaryExcludesDeletedAccountsAndEnsuresConsistentTotals() {
		when(accounts.countByStatus(AccountStatus.ACTIVE)).thenReturn(100L);
		when(accounts.countByStatus(AccountStatus.PENDING_EMAIL_VERIFICATION)).thenReturn(15L);
		when(accounts.countByStatus(AccountStatus.DISABLED)).thenReturn(5L);
		when(accounts.countByStatus(AccountStatus.DELETION_PENDING)).thenReturn(0L);
		when(accounts.countByRoleAndStatusNot(RoleCode.USER, AccountStatus.DELETED)).thenReturn(95L);
		when(accounts.countByRoleAndStatusNot(RoleCode.SPECIALIST, AccountStatus.DELETED)).thenReturn(20L);
		when(accounts.countByRoleAndStatusNot(RoleCode.ADMIN, AccountStatus.DELETED)).thenReturn(5L);

		var summary = service.getAccountsSummary();

		assertThat(summary.source()).isEqualTo("IDENTITY");
		assertThat(summary.asOf()).isEqualTo(NOW);
		assertThat(summary.totalAccounts()).isEqualTo(120L);
		assertThat(summary.activeAccounts()).isEqualTo(100L);
		assertThat(summary.pendingVerificationAccounts()).isEqualTo(15L);
		assertThat(summary.disabledAccounts()).isEqualTo(5L);
		assertThat(summary.deletionPendingAccounts()).isEqualTo(0L);
		assertThat(summary.byRole().users()).isEqualTo(95L);
		assertThat(summary.byRole().specialists()).isEqualTo(20L);
		assertThat(summary.byRole().admins()).isEqualTo(5L);

		// Assert invariant: totalAccounts equals sum of active/manageable status counts
		long sumOfStatuses = summary.activeAccounts() + summary.pendingVerificationAccounts()
				+ summary.disabledAccounts() + summary.deletionPendingAccounts();
		assertThat(summary.totalAccounts()).isEqualTo(sumOfStatuses);

		// Assert invariant: totalAccounts equals sum of roles
		long sumOfRoles = summary.byRole().users() + summary.byRole().specialists() + summary.byRole().admins();
		assertThat(summary.totalAccounts()).isEqualTo(sumOfRoles);
	}

	private AccountEntity active(RoleCode role) {
		AccountEntity account = AccountEntity.pending("user@example.com", "hash", role, NOW.minusSeconds(3600));
		account.activate(NOW.minusSeconds(1800));
		return account;
	}
}
