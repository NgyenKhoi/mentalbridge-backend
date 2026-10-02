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
	void rejectsStaleVersionDedicatedAdminAndInvalidReason() {
		AccountEntity user = active(RoleCode.USER);
		when(accounts.findByIdForUpdate(user.id())).thenReturn(Optional.of(user));
		assertThatThrownBy(() -> service.changeAccountState(UUID.randomUUID(), user.id(),
				new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.SAFETY_CONCERN),
				user.version() + 1, UUID.randomUUID())).isInstanceOf(AccountVersionMismatchException.class);

		AccountEntity admin = active(RoleCode.ADMIN);
		when(accounts.findByIdForUpdate(admin.id())).thenReturn(Optional.of(admin));
		assertThatThrownBy(() -> service.changeAccountState(UUID.randomUUID(), admin.id(),
				new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.SAFETY_CONCERN),
				admin.version(), UUID.randomUUID())).isInstanceOf(DedicatedAdminProtectionException.class);

		assertThatThrownBy(() -> service.changeAccountState(UUID.randomUUID(), user.id(),
				new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.REVIEW_COMPLETED),
				user.version(), UUID.randomUUID())).isInstanceOf(InvalidStateTransitionException.class);
	}

	private AccountEntity active(RoleCode role) {
		AccountEntity account = AccountEntity.pending("user@example.com", "hash", role, NOW.minusSeconds(3600));
		account.activate(NOW.minusSeconds(1800));
		return account;
	}
}
