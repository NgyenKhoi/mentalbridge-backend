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
import org.springframework.web.server.ResponseStatusException;

import com.mentalbridge.identity.account.AccountEntity;
import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.account.SecurityAuditEventEntity;
import com.mentalbridge.identity.authentication.AuthenticationPersistence;
import com.mentalbridge.identity.reporting.PlatformReportProcessor;

class AdminRetentionGovernanceServiceTests {

	private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

	private RetentionExecutionRecordRepository executionRecords;
	private DeletionRequestRepository deletionRequests;
	private AccountRepository accounts;
	private AuthenticationPersistence authentication;
	private RetentionAuditLogger auditLogger;
	private PlatformReportProcessor reportProcessor;
	private AdminRetentionGovernanceService service;

	@BeforeEach
	void setUp() {
		executionRecords = mock(RetentionExecutionRecordRepository.class);
		deletionRequests = mock(DeletionRequestRepository.class);
		accounts = mock(AccountRepository.class);
		authentication = mock(AuthenticationPersistence.class);
		auditLogger = mock(RetentionAuditLogger.class);
		reportProcessor = mock(PlatformReportProcessor.class);
		service = new AdminRetentionGovernanceService(executionRecords, deletionRequests, accounts, authentication,
				auditLogger, reportProcessor, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void getRetentionPoliciesReturnsAllApprovedPoliciesWithAuthoritativeOutcomes() {
		var execRecord = new RetentionExecutionRecordEntity(
				UUID.randomUUID(), "MB-IDENTITY-REPORTING-001", "IDENTITY", "REPORT_ARTIFACT_RETENTION_SWEEP",
				"SUCCEEDED", NOW.minusSeconds(3600), NOW.minusSeconds(3500), 50, 12, 0, 0, null, NOW.minusSeconds(3600)
		);
		when(executionRecords.findFirstByPolicyIdOrderByStartedAtDesc("MB-IDENTITY-REPORTING-001"))
				.thenReturn(Optional.of(execRecord));

		List<AdminRetentionGovernanceService.RetentionPolicyResponse> policies = service.getRetentionPolicies();

		assertThat(policies).hasSize(7);

		var reportingPolicy = policies.stream()
				.filter(p -> "MB-IDENTITY-REPORTING-001".equals(p.policyId())).findFirst().orElseThrow();
		assertThat(reportingPolicy.ownerService()).isEqualTo(RetentionOwnerService.IDENTITY);
		assertThat(reportingPolicy.status()).isEqualTo(RetentionPolicyStatus.APPROVED);
		assertThat(reportingPolicy.automatedExecutionSupported()).isTrue();
		assertThat(reportingPolicy.lastExecution()).isNotNull();
		assertThat(reportingPolicy.lastExecution().recordsPurged()).isEqualTo(12);

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
	void triggerRetentionSweepSucceedsForSupportedReportingPolicyWithAuthoritativeMetrics() {
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		when(reportProcessor.purgeExpiredArtifactsWithMetrics())
				.thenReturn(new PlatformReportProcessor.PurgeResult(50, 5));

		var result = service.triggerRetentionSweep("MB-IDENTITY-REPORTING-001", actorId, correlationId);

		assertThat(result.jobStatus()).isEqualTo("SUCCEEDED");
		assertThat(result.itemsEvaluated()).isEqualTo(50);
		assertThat(result.recordsPurged()).isEqualTo(5);
		verify(executionRecords).save(any(RetentionExecutionRecordEntity.class));
		verify(auditLogger).record(any(SecurityAuditEventEntity.class));
	}

	@Test
	void triggerRetentionSweepFailsClosedForUnsupportedCareOrContentPolicyAndAuditsDenied() {
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();

		assertThatThrownBy(() -> service.triggerRetentionSweep("MB-PRIVACY-CARE-001", actorId, correlationId))
				.isInstanceOf(UnsupportedRetentionCapabilityException.class)
				.hasMessageContaining("không hỗ trợ thực thi tự động");

		verify(auditLogger).record(any(SecurityAuditEventEntity.class));
	}

	@Test
	void getDeletionRequestsReturnsTraceableMinimizedIdentifiersWithoutSensitiveContent() {
		UUID accountId = UUID.randomUUID();
		String subjectHash = "a".repeat(64);
		var request = new DeletionRequestEntity(
				UUID.randomUUID(), accountId, subjectHash, DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3, DeletionEscalationState.NONE, null,
				NOW, NOW.minusSeconds(10), null, null, NOW, NOW
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
	void retryDeletionFailsWhenHoldIsActiveAndAuditsDenied() {
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

		verify(auditLogger).record(any(SecurityAuditEventEntity.class));
	}

	@Test
	void retryDeletionFailsWhenScheduledExecutionTimeIsInFuture() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		Instant futureSchedule = NOW.plusSeconds(3600);
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "f".repeat(64), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, NOW, futureSchedule, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.retryDeletion(requestId, actorId, UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Chưa đến thời điểm thực thi được phê duyệt");

		verify(auditLogger).record(any(SecurityAuditEventEntity.class));
	}

	@Test
	void retryDeletionFailsWhenAlreadyCompleted() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "c".repeat(64), DeletionRequestStatus.COMPLETED,
				DeletionHoldType.NONE, null, 1, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, NOW, NOW, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.retryDeletion(requestId, actorId, UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("đã hoàn tất");

		verify(auditLogger).record(any(SecurityAuditEventEntity.class));
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

		verify(auditLogger).record(any(SecurityAuditEventEntity.class));
	}

	@Test
	void retryDeletionDoesNotFabricateCrossOwnerFactsAndKeepsInProgress() {
		UUID requestId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var account = AccountEntity.pending("user@example.com", "hash", RoleCode.USER, NOW);
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
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.CARE, DeletionFactStatus.PENDING,
				null, null, null, NOW
		));

		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));
		when(accounts.findById(accountId)).thenReturn(Optional.of(account));

		var response = service.retryDeletion(requestId, actorId, UUID.randomUUID());

		// Identity fact is completed
		var identityFact = response.ownerFacts().stream()
				.filter(f -> f.ownerService() == RetentionOwnerService.IDENTITY).findFirst().orElseThrow();
		assertThat(identityFact.status()).isEqualTo(DeletionFactStatus.COMPLETED);
		assertThat(identityFact.evidenceReference()).startsWith("audit:identity:account-deleted:");

		// CARE fact MUST NOT be fabricated - remains PENDING!
		var careFact = response.ownerFacts().stream()
				.filter(f -> f.ownerService() == RetentionOwnerService.CARE).findFirst().orElseThrow();
		assertThat(careFact.status()).isEqualTo(DeletionFactStatus.PENDING);
		assertThat(careFact.evidenceReference()).isNull();

		// Overall request CANNOT be marked COMPLETED without all owner facts!
		assertThat(response.status()).isEqualTo(DeletionRequestStatus.IN_PROGRESS);
		assertThat(account.status()).isEqualTo(AccountStatus.DELETED);
		verify(authentication).revokeAll(eq(account.id()), eq("ACCOUNT_DELETED"), any(Instant.class));
		verify(auditLogger).record(any(SecurityAuditEventEntity.class));
	}

	@Test
	void applyHoldBlocksDeletionAndRejectsUnsafeReason() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "e".repeat(64), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		// Rejects free-text / unsafe reason
		assertThatThrownBy(() -> service.applyHold(requestId, DeletionHoldType.LEGAL_HOLD, "FREE_TEXT_LEAK", actorId, UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("không hợp lệ");

		// Accepts approved safe reason code
		var response = service.applyHold(requestId, DeletionHoldType.LEGAL_HOLD, "STATUTORY_INQUIRY", actorId, UUID.randomUUID());

		assertThat(response.status()).isEqualTo(DeletionRequestStatus.HELD);
		assertThat(response.holdType()).isEqualTo(DeletionHoldType.LEGAL_HOLD);
		assertThat(response.holdReason()).isEqualTo("STATUTORY_INQUIRY");
		verify(auditLogger).record(any(SecurityAuditEventEntity.class));
	}

	@Test
	void releaseHoldRestoresPendingStatusAndAudits() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "f".repeat(64), DeletionRequestStatus.HELD,
				DeletionHoldType.OPERATIONAL_HOLD, "ACTIVE_SAFETY_REVIEW", 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		var response = service.releaseHold(requestId, actorId, UUID.randomUUID());

		assertThat(response.status()).isEqualTo(DeletionRequestStatus.PENDING);
		assertThat(response.holdType()).isEqualTo(DeletionHoldType.NONE);
		verify(auditLogger).record(any(SecurityAuditEventEntity.class));
	}
}
