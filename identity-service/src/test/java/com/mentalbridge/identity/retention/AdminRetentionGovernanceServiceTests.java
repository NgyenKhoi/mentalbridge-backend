package com.mentalbridge.identity.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.mentalbridge.identity.account.AccountEntity;
import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.account.SecurityAuditEventEntity;
import com.mentalbridge.identity.account.SecurityAuditEventRepository;
import com.mentalbridge.identity.authentication.AuthenticationPersistence;
import com.mentalbridge.identity.reporting.PlatformReportProcessor;

class AdminRetentionGovernanceServiceTests {

	private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

	private RetentionExecutionRecordRepository executionRecords;
	private DeletionRequestRepository deletionRequests;
	private AccountRepository accounts;
	private AuthenticationPersistence authentication;
	private SecurityAuditEventRepository auditEvents;
	private PlatformReportProcessor reportProcessor;
	private AdminRetentionGovernanceService service;

	@BeforeEach
	void setUp() {
		executionRecords = mock(RetentionExecutionRecordRepository.class);
		deletionRequests = mock(DeletionRequestRepository.class);
		accounts = mock(AccountRepository.class);
		authentication = mock(AuthenticationPersistence.class);
		auditEvents = mock(SecurityAuditEventRepository.class);
		reportProcessor = mock(PlatformReportProcessor.class);
		service = new AdminRetentionGovernanceService(executionRecords, deletionRequests, accounts, authentication,
				auditEvents, reportProcessor, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void getRetentionPoliciesReturnsAllApprovedPoliciesWithAuthoritativeOutcomes() {
		var execRecord = new RetentionExecutionRecordEntity(
				UUID.randomUUID(), "MB-IDENTITY-RETENTION-001", "IDENTITY", "IDENTITY_RETENTION_SWEEP",
				"SUCCEEDED", NOW.minusSeconds(3600), NOW.minusSeconds(3500), 50, 12, 5, 0, null, NOW.minusSeconds(3600)
		);
		when(executionRecords.findFirstByPolicyIdOrderByStartedAtDesc("MB-IDENTITY-RETENTION-001"))
				.thenReturn(Optional.of(execRecord));

		List<AdminRetentionGovernanceService.RetentionPolicyResponse> policies = service.getRetentionPolicies();

		assertThat(policies).hasSize(6);

		var identityPolicy = policies.stream()
				.filter(p -> "MB-IDENTITY-RETENTION-001".equals(p.policyId())).findFirst().orElseThrow();
		assertThat(identityPolicy.ownerService()).isEqualTo(RetentionOwnerService.IDENTITY);
		assertThat(identityPolicy.status()).isEqualTo(RetentionPolicyStatus.APPROVED);
		assertThat(identityPolicy.automatedExecutionSupported()).isTrue();
		assertThat(identityPolicy.lastExecution()).isNotNull();
		assertThat(identityPolicy.lastExecution().recordsPurged()).isEqualTo(12);

		var carePolicy = policies.stream()
				.filter(p -> "MB-PRIVACY-CARE-001".equals(p.policyId())).findFirst().orElseThrow();
		assertThat(carePolicy.version()).isEqualTo("1.1-capstone");
		assertThat(carePolicy.automatedExecutionSupported()).isFalse();
		assertThat(carePolicy.unsupportedCapabilityNotice()).isNotBlank();

		var contentPolicy = policies.stream()
				.filter(p -> "MB-CONTENT-NOTIFICATION-RETENTION-001".equals(p.policyId())).findFirst().orElseThrow();
		assertThat(contentPolicy.jobStatus()).isEqualTo(RetentionJobStatus.UNAVAILABLE);
		assertThat(contentPolicy.automatedExecutionSupported()).isFalse();
	}

	@Test
	void triggerRetentionSweepSucceedsForSupportedIdentityPolicyAndAudits() {
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		when(reportProcessor.purgeExpiredArtifacts()).thenReturn(5L);

		var result = service.triggerRetentionSweep("MB-IDENTITY-RETENTION-001", actorId, correlationId);

		assertThat(result.jobStatus()).isEqualTo("SUCCEEDED");
		assertThat(result.recordsPurged()).isEqualTo(5);
		verify(executionRecords).save(any(RetentionExecutionRecordEntity.class));
		verify(auditEvents).save(any(SecurityAuditEventEntity.class));
	}

	@Test
	void triggerRetentionSweepFailsClosedForUnsupportedCareOrContentPolicyAndAuditsDenied() {
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();

		assertThatThrownBy(() -> service.triggerRetentionSweep("MB-PRIVACY-CARE-001", actorId, correlationId))
				.isInstanceOf(UnsupportedRetentionCapabilityException.class)
				.hasMessageContaining("không hỗ trợ thực thi tự động");

		verify(auditEvents).save(any(SecurityAuditEventEntity.class));
	}

	@Test
	void getDeletionRequestsReturnsTraceableMinimizedIdentifiersWithoutSensitiveContent() {
		UUID accountId = UUID.randomUUID();
		String subjectHash = "a".repeat(64);
		var request = new DeletionRequestEntity(
				UUID.randomUUID(), accountId, subjectHash, DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3, DeletionEscalationState.NONE, null,
				NOW, NOW.plusSeconds(86400), null, null, NOW, NOW
		);
		var fact = new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.CARE, DeletionFactStatus.PENDING,
				null, null, null, NOW
		);
		request.addOwnerFact(fact);

		when(deletionRequests.findAll(any(Specification.class), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(request)));
		when(accounts.findById(accountId)).thenReturn(Optional.of(AccountEntity.pending("user@example.com", "hash", RoleCode.USER, NOW)));

		var page = service.getDeletionRequests(null, null, 10);

		assertThat(page.items()).hasSize(1);
		var item = page.items().getFirst();
		assertThat(item.subjectReference()).isEqualTo("tombstone:" + subjectHash);
		assertThat(item.ownerFacts()).hasSize(1);
		assertThat(item.ownerFacts().getFirst().ownerService()).isEqualTo(RetentionOwnerService.CARE);
	}

	@Test
	void retryDeletionFailsWhenHoldIsActive() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "b".repeat(64), DeletionRequestStatus.HELD,
				DeletionHoldType.LEGAL_HOLD, "STATUTORY_INQUIRY", 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.retryDeletion(requestId, actorId, UUID.randomUUID()))
				.isInstanceOf(DeletionHoldActiveException.class)
				.hasMessageContaining("LEGAL_HOLD");

		verify(auditEvents).save(any(SecurityAuditEventEntity.class));
	}

	@Test
	void retryDeletionEnforcesBoundedRetryLimitAndEscalates() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "c".repeat(64), DeletionRequestStatus.FAILED,
				DeletionHoldType.NONE, null, 3, 3,
				DeletionEscalationState.NONE, "TIMEOUT", NOW, NOW, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.retryDeletion(requestId, actorId, UUID.randomUUID()))
				.isInstanceOf(DeletionRetryExhaustedException.class)
				.hasMessageContaining("giới hạn tối đa");

		verify(auditEvents).save(any(SecurityAuditEventEntity.class));
	}

	@Test
	void retryDeletionSucceedsWhenNoHoldAndUnderLimit() {
		UUID requestId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var account = AccountEntity.pending("delete-me@example.com", "hash", RoleCode.USER, NOW);
		account.activate(NOW);

		var request = new DeletionRequestEntity(
				requestId, accountId, "d".repeat(64), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.PENDING,
				null, null, null, NOW
		));

		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));
		when(accounts.findById(accountId)).thenReturn(Optional.of(account));

		var response = service.retryDeletion(requestId, actorId, UUID.randomUUID());

		assertThat(response.status()).isEqualTo(DeletionRequestStatus.COMPLETED);
		assertThat(response.retryCount()).isEqualTo(1);
		assertThat(account.status()).isEqualTo(AccountStatus.DELETED);
		verify(authentication).revokeAll(eq(account.id()), eq("ACCOUNT_DELETED"), any(Instant.class));
		verify(auditEvents).save(any(SecurityAuditEventEntity.class));
	}

	@Test
	void applyHoldBlocksDeletionAndAudits() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "e".repeat(64), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		var response = service.applyHold(requestId, DeletionHoldType.LEGAL_HOLD, "COURT_ORDER", actorId, UUID.randomUUID());

		assertThat(response.status()).isEqualTo(DeletionRequestStatus.HELD);
		assertThat(response.holdType()).isEqualTo(DeletionHoldType.LEGAL_HOLD);
		assertThat(response.holdReason()).isEqualTo("COURT_ORDER");
		verify(auditEvents).save(any(SecurityAuditEventEntity.class));
	}

	@Test
	void releaseHoldRestoresPendingStatusAndAudits() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "f".repeat(64), DeletionRequestStatus.HELD,
				DeletionHoldType.OPERATIONAL_HOLD, "PENDING_AUDIT", 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		var response = service.releaseHold(requestId, actorId, UUID.randomUUID());

		assertThat(response.status()).isEqualTo(DeletionRequestStatus.PENDING);
		assertThat(response.holdType()).isEqualTo(DeletionHoldType.NONE);
		verify(auditEvents).save(any(SecurityAuditEventEntity.class));
	}
}

