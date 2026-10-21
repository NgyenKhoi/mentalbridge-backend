package com.mentalbridge.identity.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
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
	private RetentionExecutionRecorder executionRecorder;
	private AdminRetentionGovernanceService service;

	@BeforeEach
	void setUp() {
		executionRecords = mock(RetentionExecutionRecordRepository.class);
		deletionRequests = mock(DeletionRequestRepository.class);
		accounts = mock(AccountRepository.class);
		authentication = mock(AuthenticationPersistence.class);
		auditLogger = mock(RetentionAuditLogger.class);
		reportProcessor = mock(PlatformReportProcessor.class);
		executionRecorder = mock(RetentionExecutionRecorder.class);
		service = new AdminRetentionGovernanceService(executionRecords, deletionRequests, accounts, authentication,
				auditLogger, reportProcessor, Clock.fixed(NOW, ZoneOffset.UTC), executionRecorder);
		when(executionRecords.tryAcquirePolicyExecutionLock(any())).thenReturn(true);
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
		verify(executionRecorder).recordStart(any(RetentionExecutionRecordEntity.class));
		verify(executionRecorder).recordSuccess(any(RetentionExecutionRecordEntity.class));
		verify(auditLogger).recordSuccess(any(SecurityAuditEventEntity.class));
	}

	@Test
	void triggerRetentionSweepFailsClosedForUnsupportedCareOrContentPolicyAndAuditsDenied() {
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();

		assertThatThrownBy(() -> service.triggerRetentionSweep("MB-PRIVACY-CARE-001", actorId, correlationId))
				.isInstanceOf(UnsupportedRetentionCapabilityException.class)
				.hasMessageContaining("không hỗ trợ thực thi tự động");

		verify(auditLogger).recordDenied(any(SecurityAuditEventEntity.class));
	}

	@Test
	void triggerRetentionSweepRecordsFailedExecutionWhenProcessorThrows() {
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		when(reportProcessor.purgeExpiredArtifactsWithMetrics())
				.thenThrow(new RuntimeException("Database timeout"));

		assertThatThrownBy(() -> service.triggerRetentionSweep("MB-IDENTITY-REPORTING-001", actorId, correlationId))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Thực thi dọn dẹp thất bại");

		verify(executionRecorder).recordFailure(any(RetentionExecutionRecordEntity.class));
		verify(auditLogger).recordDenied(any(SecurityAuditEventEntity.class));
	}

	@Test
	void triggerRetentionSweepBlocksWhenRecentThreeExecutionsFailedOrStaleWithConflict() {
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		var f1 = new RetentionExecutionRecordEntity(UUID.randomUUID(), "MB-IDENTITY-REPORTING-001", "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "FAILED", NOW.minusSeconds(300), NOW.minusSeconds(290),
				10, 0, 0, 0, "TIMEOUT", NOW.minusSeconds(300));
		var f2 = new RetentionExecutionRecordEntity(UUID.randomUUID(), "MB-IDENTITY-REPORTING-001", "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "FAILED", NOW.minusSeconds(200), NOW.minusSeconds(190),
				10, 0, 0, 0, "TIMEOUT", NOW.minusSeconds(200));
		var f3 = new RetentionExecutionRecordEntity(UUID.randomUUID(), "MB-IDENTITY-REPORTING-001", "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "STALE", NOW.minusSeconds(100), NOW.minusSeconds(90),
				10, 0, 0, 0, "PRIOR_EXECUTION_STALE", NOW.minusSeconds(100));

		when(executionRecords.findTop3ByPolicyIdOrderByStartedAtDesc("MB-IDENTITY-REPORTING-001"))
				.thenReturn(List.of(f3, f2, f1));

		assertThatThrownBy(() -> service.triggerRetentionSweep("MB-IDENTITY-REPORTING-001", actorId, correlationId))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("thất bại liên tiếp 3 lần");

		verify(auditLogger).recordDenied(argThat(event -> "RETRY_EXHAUSTED".equals(event.getReasonCode())));
	}

	@Test
	void getRetentionPoliciesAndTriggerSweepAgreeOnOrphanedRetryExhaustion() {
		String policyId = "MB-IDENTITY-REPORTING-001";
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		var failedOlder = new RetentionExecutionRecordEntity(UUID.randomUUID(), policyId, "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "FAILED", NOW.minusSeconds(300), NOW.minusSeconds(290),
				0, 0, 0, 0, "SWEEP_EXECUTION_FAILED", NOW.minusSeconds(300));
		var failedNewer = new RetentionExecutionRecordEntity(UUID.randomUUID(), policyId, "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "FAILED", NOW.minusSeconds(200), NOW.minusSeconds(190),
				0, 0, 0, 0, "SWEEP_EXECUTION_FAILED", NOW.minusSeconds(200));
		var orphaned = new RetentionExecutionRecordEntity(UUID.randomUUID(), policyId, "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "RUNNING", NOW.minusSeconds(100), null,
				0, 0, 0, 0, null, NOW.minusSeconds(100));
		when(executionRecords.findFirstByPolicyIdAndJobStatusOrderByStartedAtDesc(policyId, "RUNNING"))
				.thenReturn(Optional.of(orphaned));
		when(executionRecords.findFirstByPolicyIdOrderByStartedAtDesc(policyId))
				.thenReturn(Optional.of(orphaned));
		when(executionRecords.findTop3ByPolicyIdOrderByStartedAtDesc(policyId))
				.thenReturn(List.of(orphaned, failedNewer, failedOlder));

		var policy = service.getRetentionPolicies().stream()
				.filter(candidate -> policyId.equals(candidate.policyId())).findFirst().orElseThrow();
		assertThat(policy.jobStatus()).isEqualTo(RetentionJobStatus.FAILED);
		assertThat(policy.lastExecution().jobStatus()).isEqualTo("STALE");
		verify(executionRecorder, never()).recordStale(any());

		assertThatThrownBy(() -> service.triggerRetentionSweep(policyId, actorId, correlationId))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("thất bại liên tiếp 3 lần");

		InOrder order = inOrder(executionRecords, executionRecorder);
		order.verify(executionRecords).tryAcquirePolicyExecutionLock(policyId);
		order.verify(executionRecords).findFirstByPolicyIdAndJobStatusOrderByStartedAtDesc(policyId, "RUNNING");
		order.verify(executionRecorder).recordStale(argThat(record -> orphaned.id().equals(record.id())
				&& "STALE".equals(record.jobStatus()) && "PRIOR_EXECUTION_STALE".equals(record.failureCode())));
		order.verify(executionRecords).findTop3ByPolicyIdOrderByStartedAtDesc(policyId);
		verify(auditLogger).recordDenied(argThat(event -> "RETRY_EXHAUSTED".equals(event.getReasonCode())
				&& correlationId.equals(event.getCorrelationId())));
		verify(executionRecorder, never()).recordStart(any());
		verify(reportProcessor, never()).purgeExpiredArtifactsWithMetrics();
	}

	@Test
	void getRetentionPoliciesReflectsFailedWhenNewerIsFailedAndPriorExecutionIsOrphanedRunning() {
		String policyId = "MB-IDENTITY-REPORTING-001";
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		var failedOlder = new RetentionExecutionRecordEntity(UUID.randomUUID(), policyId, "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "FAILED", NOW.minusSeconds(300), NOW.minusSeconds(290),
				0, 0, 0, 0, "SWEEP_EXECUTION_FAILED", NOW.minusSeconds(300));
		var orphaned = new RetentionExecutionRecordEntity(UUID.randomUUID(), policyId, "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "RUNNING", NOW.minusSeconds(200), null,
				0, 0, 0, 0, null, NOW.minusSeconds(200));
		var failedNewer = new RetentionExecutionRecordEntity(UUID.randomUUID(), policyId, "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "FAILED", NOW.minusSeconds(100), NOW.minusSeconds(90),
				0, 0, 0, 0, "SWEEP_EXECUTION_FAILED", NOW.minusSeconds(100));

		when(executionRecords.findFirstByPolicyIdOrderByStartedAtDesc(policyId))
				.thenReturn(Optional.of(failedNewer));
		when(executionRecords.findTop3ByPolicyIdOrderByStartedAtDesc(policyId))
				.thenReturn(List.of(failedNewer, orphaned, failedOlder));
		when(executionRecords.findFirstByPolicyIdAndJobStatusOrderByStartedAtDesc(policyId, "RUNNING"))
				.thenReturn(Optional.of(orphaned));
		when(executionRecords.tryAcquirePolicyExecutionLock(policyId)).thenReturn(true);

		var policy = service.getRetentionPolicies().stream()
				.filter(candidate -> policyId.equals(candidate.policyId())).findFirst().orElseThrow();

		assertThat(policy.jobStatus()).isEqualTo(RetentionJobStatus.FAILED);
		assertThat(policy.lastExecution()).isNotNull();
		assertThat(policy.lastExecution().jobStatus()).isEqualTo("FAILED");

		assertThatThrownBy(() -> service.triggerRetentionSweep(policyId, actorId, correlationId))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("thất bại liên tiếp 3 lần");

		verify(auditLogger).recordDenied(argThat(event -> "RETRY_EXHAUSTED".equals(event.getReasonCode())
				&& correlationId.equals(event.getCorrelationId())));
	}

	@Test
	void getRetentionPoliciesTreatsUnresolvedRunningAsRunningWhenLockLookupFails() {
		String policyId = "MB-IDENTITY-REPORTING-001";
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		var failedOlder = new RetentionExecutionRecordEntity(UUID.randomUUID(), policyId, "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "FAILED", NOW.minusSeconds(300), NOW.minusSeconds(290),
				0, 0, 0, 0, "SWEEP_EXECUTION_FAILED", NOW.minusSeconds(300));
		var runningPrior = new RetentionExecutionRecordEntity(UUID.randomUUID(), policyId, "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "RUNNING", NOW.minusSeconds(200), null,
				0, 0, 0, 0, null, NOW.minusSeconds(200));
		var failedNewer = new RetentionExecutionRecordEntity(UUID.randomUUID(), policyId, "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "FAILED", NOW.minusSeconds(100), NOW.minusSeconds(90),
				0, 0, 0, 0, "SWEEP_EXECUTION_FAILED", NOW.minusSeconds(100));

		when(executionRecords.findFirstByPolicyIdOrderByStartedAtDesc(policyId))
				.thenReturn(Optional.of(failedNewer));
		when(executionRecords.findTop3ByPolicyIdOrderByStartedAtDesc(policyId))
				.thenReturn(List.of(failedNewer, runningPrior, failedOlder));
		when(executionRecords.tryAcquirePolicyExecutionLock(policyId))
				.thenThrow(new RuntimeException("PostgreSQL advisory lock lookup failed"));

		var policy = service.getRetentionPolicies().stream()
				.filter(candidate -> policyId.equals(candidate.policyId())).findFirst().orElseThrow();

		assertThat(policy.jobStatus()).isEqualTo(RetentionJobStatus.RUNNING);
		assertThat(policy.lastExecution()).isNotNull();
		assertThat(policy.lastExecution().jobStatus()).isEqualTo("FAILED");

		assertThatThrownBy(() -> service.triggerRetentionSweep(policyId, actorId, correlationId))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Không thể xác thực khóa thực thi độc quyền");

		verify(auditLogger).recordDenied(argThat(event -> "LOCK_ACQUISITION_FAILED".equals(event.getReasonCode())
				&& correlationId.equals(event.getCorrelationId())));
	}

	@Test
	void triggerRetentionSweepDetectsOrphanedRunningRecordAsStaleWhenLockAcquired() {
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		var running = new RetentionExecutionRecordEntity(UUID.randomUUID(), "MB-IDENTITY-REPORTING-001", "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "RUNNING", NOW.minusSeconds(100), null,
				0, 0, 0, 0, null, NOW.minusSeconds(100));

		when(executionRecords.tryAcquirePolicyExecutionLock("MB-IDENTITY-REPORTING-001")).thenReturn(true);
		when(executionRecords.findFirstByPolicyIdAndJobStatusOrderByStartedAtDesc("MB-IDENTITY-REPORTING-001", "RUNNING"))
				.thenReturn(Optional.of(running));
		when(reportProcessor.purgeExpiredArtifactsWithMetrics())
				.thenReturn(new PlatformReportProcessor.PurgeResult(20, 2));

		var result = service.triggerRetentionSweep("MB-IDENTITY-REPORTING-001", actorId, correlationId);

		assertThat(result.jobStatus()).isEqualTo("SUCCEEDED");
		verify(executionRecorder).recordStale(argThat(record ->
				"STALE".equals(record.jobStatus()) && "PRIOR_EXECUTION_STALE".equals(record.failureCode())));
		verify(executionRecorder).recordStart(any(RetentionExecutionRecordEntity.class));
		verify(executionRecorder).recordSuccess(any(RetentionExecutionRecordEntity.class));
	}

	@Test
	void triggerRetentionSweepFailClosedWhenLockThrowsException() {
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		when(executionRecords.tryAcquirePolicyExecutionLock("MB-IDENTITY-REPORTING-001"))
				.thenThrow(new RuntimeException("PostgreSQL advisory lock lookup error"));

		assertThatThrownBy(() -> service.triggerRetentionSweep("MB-IDENTITY-REPORTING-001", actorId, correlationId))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("Không thể xác thực khóa thực thi độc quyền");

		verify(auditLogger).recordDenied(argThat(event -> "LOCK_ACQUISITION_FAILED".equals(event.getReasonCode())));
		verify(reportProcessor, never()).purgeExpiredArtifactsWithMetrics();
		verify(executionRecorder, never()).recordSuccess(any(RetentionExecutionRecordEntity.class));
	}

	@Test
	void getRetentionPoliciesDoesNotInferStaleWhenLockLookupFails() {
		var runningExec = new RetentionExecutionRecordEntity(UUID.randomUUID(), "MB-IDENTITY-REPORTING-001", "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "RUNNING", NOW.minusSeconds(200), null,
				0, 0, 0, 0, null, NOW.minusSeconds(200));

		when(executionRecords.findFirstByPolicyIdOrderByStartedAtDesc("MB-IDENTITY-REPORTING-001"))
				.thenReturn(Optional.of(runningExec));
		when(executionRecords.tryAcquirePolicyExecutionLock("MB-IDENTITY-REPORTING-001"))
				.thenThrow(new RuntimeException("Connection error"));

		var policies = service.getRetentionPolicies();

		var reportingPolicy = policies.stream()
				.filter(p -> "MB-IDENTITY-REPORTING-001".equals(p.policyId()))
				.findFirst()
				.orElseThrow();

		assertThat(reportingPolicy.jobStatus()).isEqualTo(RetentionJobStatus.RUNNING);
		assertThat(reportingPolicy.lastExecution()).isNotNull();
		assertThat(reportingPolicy.lastExecution().jobStatus()).isEqualTo("RUNNING");
	}

	@Test
	void triggerRetentionSweepBlocksConcurrentExecutionWhenLockCannotBeAcquired() {
		UUID actorId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();

		when(executionRecords.tryAcquirePolicyExecutionLock("MB-IDENTITY-REPORTING-001")).thenReturn(false);

		assertThatThrownBy(() -> service.triggerRetentionSweep("MB-IDENTITY-REPORTING-001", actorId, correlationId))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("đang được thực thi (RUNNING)");

		verify(auditLogger).recordDenied(argThat(event -> "CONCURRENT_SWEEP_RUNNING".equals(event.getReasonCode())));
	}

	@Test
	void getRetentionPoliciesReflectsStaleOrFailedJobStatus() {
		var staleExec = new RetentionExecutionRecordEntity(UUID.randomUUID(), "MB-IDENTITY-REPORTING-001", "IDENTITY",
				"REPORT_ARTIFACT_RETENTION_SWEEP", "RUNNING", NOW.minusSeconds(200), null,
				0, 0, 0, 0, null, NOW.minusSeconds(200));

		when(executionRecords.findFirstByPolicyIdOrderByStartedAtDesc("MB-IDENTITY-REPORTING-001"))
				.thenReturn(Optional.of(staleExec));
		when(executionRecords.tryAcquirePolicyExecutionLock("MB-IDENTITY-REPORTING-001")).thenReturn(true);

		var policies = service.getRetentionPolicies();

		var reportingPolicy = policies.stream()
				.filter(p -> "MB-IDENTITY-REPORTING-001".equals(p.policyId()))
				.findFirst()
				.orElseThrow();

		assertThat(reportingPolicy.jobStatus()).isEqualTo(RetentionJobStatus.STALE);
		assertThat(reportingPolicy.lastExecution()).isNotNull();
		assertThat(reportingPolicy.lastExecution().jobStatus()).isEqualTo("STALE");
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
	void getDeletionRequestsWithPaginationSupportsPageNumberAndNextCursor() {
		UUID accountId = UUID.randomUUID();
		var request1 = new DeletionRequestEntity(
				UUID.randomUUID(), accountId, "p1".repeat(32), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3, DeletionEscalationState.NONE, null,
				NOW, NOW, null, null, NOW, NOW
		);
		var pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
		when(deletionRequests.findAll(any(Specification.class), pageableCaptor.capture()))
				.thenReturn(new PageImpl<>(List.of(request1), org.springframework.data.domain.PageRequest.of(0, 1), 5));
		when(accounts.findById(accountId)).thenReturn(Optional.of(AccountEntity.pending("user@example.com", "hash", RoleCode.USER, NOW)));

		var page = service.getDeletionRequests(null, null, 1, 0);

		assertThat(page.items()).hasSize(1);
		assertThat(page.total()).isEqualTo(5);
		assertThat(page.nextCursor()).isEqualTo("1");
		assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
		assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);
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

		verify(auditLogger).recordDenied(any(SecurityAuditEventEntity.class));
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

		verify(auditLogger).recordDenied(any(SecurityAuditEventEntity.class));
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

		verify(auditLogger).recordDenied(any(SecurityAuditEventEntity.class));
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

		verify(auditLogger).recordDenied(any(SecurityAuditEventEntity.class));
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
		assertThat(identityFact.evidenceReference()).isEqualTo("audit:identity:account-deleted:" + requestId);

		// CARE fact MUST NOT be fabricated - remains PENDING!
		var careFact = response.ownerFacts().stream()
				.filter(f -> f.ownerService() == RetentionOwnerService.CARE).findFirst().orElseThrow();
		assertThat(careFact.status()).isEqualTo(DeletionFactStatus.PENDING);
		assertThat(careFact.evidenceReference()).isNull();

		// Overall request CANNOT be marked COMPLETED without all owner facts!
		assertThat(response.status()).isEqualTo(DeletionRequestStatus.IN_PROGRESS);
		assertThat(account.status()).isEqualTo(AccountStatus.DELETED);
		verify(authentication).revokeAll(eq(account.id()), eq("ACCOUNT_DELETED"), any(Instant.class));
		verify(auditLogger).recordSuccess(any(SecurityAuditEventEntity.class));
	}

	@Test
	void retryDeletionRejectsWhenOwnerFactsIsEmptyAndAuditsDenied() {
		UUID requestId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var account = AccountEntity.pending("user@example.com", "hash", RoleCode.USER, NOW);
		account.activate(NOW);

		var request = new DeletionRequestEntity(
				requestId, accountId, "emptyfacts".repeat(6), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);

		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.retryDeletion(requestId, actorId, UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("không có danh sách owner facts hợp lệ");

		// Account status was NOT modified
		assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
		// Attempt was NOT recorded
		assertThat(request.retryCount()).isEqualTo(0);
		// Audit was recorded as DENIED
		verify(auditLogger).recordDenied(any(SecurityAuditEventEntity.class));
	}

	@Test
	void retryDeletionRejectsWhenIdentityFactMissingAndAuditsDenied() {
		UUID requestId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var account = AccountEntity.pending("user@example.com", "hash", RoleCode.USER, NOW);
		account.activate(NOW);

		var request = new DeletionRequestEntity(
				requestId, accountId, "noidentity".repeat(6), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.CARE, DeletionFactStatus.PENDING,
				null, null, null, NOW
		));

		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.retryDeletion(requestId, actorId, UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("thiếu owner fact của dịch vụ IDENTITY");

		assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
		assertThat(request.retryCount()).isEqualTo(0);
		verify(auditLogger).recordDenied(any(SecurityAuditEventEntity.class));
	}

	@Test
	void retryDeletionRejectsWithoutConsumingAttemptWhenIdentityAlreadyCompleted() {
		UUID requestId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();

		var request = new DeletionRequestEntity(
				requestId, accountId, "idcompleted".repeat(5), DeletionRequestStatus.IN_PROGRESS,
				DeletionHoldType.NONE, null, 1, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		var idFact = new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.COMPLETED,
				NOW, "audit:id:001", null, NOW
		);
		var careFact = new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.CARE, DeletionFactStatus.PENDING,
				null, null, null, NOW
		);
		request.addOwnerFact(idFact);
		request.addOwnerFact(careFact);

		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.retryDeletion(requestId, actorId, UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("chưa hỗ trợ retry tự động");

		// Attempt was NOT consumed
		assertThat(request.retryCount()).isEqualTo(1);
		verify(auditLogger).recordDenied(any(SecurityAuditEventEntity.class));
	}

	@Test
	void retryDeletionMarksIdentityFactFailedWhenAccountNotFound() {
		UUID requestId = UUID.randomUUID();
		UUID missingAccountId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();

		var request = new DeletionRequestEntity(
				requestId, missingAccountId, "missing".repeat(8), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		var identityFact = new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.PENDING,
				null, null, null, NOW
		);
		request.addOwnerFact(identityFact);

		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));
		when(accounts.findById(missingAccountId)).thenReturn(Optional.empty());

		var response = service.retryDeletion(requestId, actorId, UUID.randomUUID());

		assertThat(response.status()).isEqualTo(DeletionRequestStatus.FAILED);
		assertThat(response.failureCode()).isEqualTo("ACCOUNT_NOT_FOUND");
		assertThat(response.accountStatus()).isNull();
		var updatedFact = response.ownerFacts().getFirst();
		assertThat(updatedFact.status()).isEqualTo(DeletionFactStatus.FAILED);
		assertThat(updatedFact.failureReason()).isEqualTo("ACCOUNT_NOT_FOUND");
		verify(auditLogger).recordDenied(argThat(event ->
				"FAILED".equals(event.getOutcome()) && "ACCOUNT_NOT_FOUND".equals(event.getReasonCode())
		));
	}

	@Test
	void retryDeletionMissingAccountWhenRetryLimitExhaustedSetsManualEscalation() {
		UUID requestId = UUID.randomUUID();
		UUID missingAccountId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();

		var request = new DeletionRequestEntity(
				requestId, missingAccountId, "missinglimit".repeat(5), DeletionRequestStatus.FAILED,
				DeletionHoldType.NONE, null, 2, 3,
				DeletionEscalationState.NONE, "PREVIOUS_ERROR", NOW, NOW, null, null, NOW, NOW
		);
		var identityFact = new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.PENDING,
				null, null, null, NOW
		);
		request.addOwnerFact(identityFact);

		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));
		when(accounts.findById(missingAccountId)).thenReturn(Optional.empty());

		var response = service.retryDeletion(requestId, actorId, UUID.randomUUID());

		assertThat(response.status()).isEqualTo(DeletionRequestStatus.FAILED);
		assertThat(response.failureCode()).isEqualTo("ACCOUNT_NOT_FOUND");
		assertThat(response.retryCount()).isEqualTo(3);
		assertThat(response.escalationState()).isEqualTo(DeletionEscalationState.MANUAL_ESCALATION_REQUIRED);
		verify(auditLogger).recordDenied(argThat(event ->
				"FAILED".equals(event.getOutcome()) && "ACCOUNT_NOT_FOUND".equals(event.getReasonCode())
		));
	}

	@Test
	void retryDeletionClearsEscalationStateWhenCompletedSuccessfully() {
		UUID requestId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var account = AccountEntity.pending("user@example.com", "hash", RoleCode.USER, NOW);
		account.activate(NOW);

		var request = new DeletionRequestEntity(
				requestId, accountId, "success".repeat(8), DeletionRequestStatus.FAILED,
				DeletionHoldType.NONE, null, 2, 3,
				DeletionEscalationState.MANUAL_ESCALATION_REQUIRED, "PREVIOUS_TIMEOUT", NOW, NOW, null, null, NOW, NOW
		);
		var identityFact = new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.PENDING,
				null, null, null, NOW
		);
		request.addOwnerFact(identityFact);

		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));
		when(accounts.findById(accountId)).thenReturn(Optional.of(account));

		var response = service.retryDeletion(requestId, actorId, UUID.randomUUID());

		assertThat(response.status()).isEqualTo(DeletionRequestStatus.COMPLETED);
		assertThat(response.escalationState()).isEqualTo(DeletionEscalationState.NONE);
		verify(auditLogger).recordSuccess(any(SecurityAuditEventEntity.class));
	}

	@Test
	void holdsDoNotMutateOwnerFacts() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "holds".repeat(10), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		var careFact = new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.CARE, DeletionFactStatus.PENDING,
				null, null, null, NOW
		);
		request.addOwnerFact(careFact);

		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		// Apply Hold: Coordinator hold must not fabricate owner HELD status
		var heldResponse = service.applyHold(requestId, DeletionHoldType.LEGAL_HOLD, DeletionHoldReason.STATUTORY_INQUIRY, actorId, UUID.randomUUID());
		assertThat(heldResponse.status()).isEqualTo(DeletionRequestStatus.HELD);
		assertThat(careFact.status()).isEqualTo(DeletionFactStatus.PENDING);

		// Release Hold: Coordinator hold release must not alter owner facts
		var releasedResponse = service.releaseHold(requestId, actorId, UUID.randomUUID());
		assertThat(releasedResponse.status()).isEqualTo(DeletionRequestStatus.PENDING);
		assertThat(careFact.status()).isEqualTo(DeletionFactStatus.PENDING);
	}

	@Test
	void applyHoldUsesGenericAuditActionWithSafeTypeAndReason() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "e".repeat(64), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.applyHold(requestId, DeletionHoldType.OPERATIONAL_HOLD, null, actorId, UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.hasMessageContaining("không hợp lệ");

		var response = service.applyHold(requestId, DeletionHoldType.OPERATIONAL_HOLD,
				DeletionHoldReason.ACTIVE_SAFETY_REVIEW, actorId, UUID.randomUUID());

		assertThat(response.status()).isEqualTo(DeletionRequestStatus.HELD);
		assertThat(response.holdType()).isEqualTo(DeletionHoldType.OPERATIONAL_HOLD);
		assertThat(response.holdReason()).isEqualTo("ACTIVE_SAFETY_REVIEW");
		verify(auditLogger).recordSuccess(argThat(event ->
				"DELETION_HOLD_APPLIED".equals(event.getAction())
						&& "OPERATIONAL_HOLD_ACTIVE_SAFETY_REVIEW".equals(event.getReasonCode())));
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
		verify(auditLogger).recordSuccess(argThat(event ->
				"DELETION_HOLD_RELEASED".equals(event.getAction())
						&& "OPERATIONAL_HOLD_ACTIVE_SAFETY_REVIEW".equals(event.getReasonCode())));
	}

	@Test
	void holdLifecycleFailedHeldReleasePreservesFailedStatus() {
		UUID requestId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, accountId, "d".repeat(64), DeletionRequestStatus.FAILED,
				DeletionHoldType.NONE, null, 1, 3,
				DeletionEscalationState.NONE, "EXTERNAL_TIMEOUT", NOW, NOW, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		var held = service.applyHold(requestId, DeletionHoldType.LEGAL_HOLD, DeletionHoldReason.STATUTORY_INQUIRY, actorId, UUID.randomUUID());
		assertThat(held.status()).isEqualTo(DeletionRequestStatus.HELD);
		assertThat(held.holdType()).isEqualTo(DeletionHoldType.LEGAL_HOLD);

		var released = service.releaseHold(requestId, actorId, UUID.randomUUID());
		assertThat(released.status()).isEqualTo(DeletionRequestStatus.FAILED);
		assertThat(released.holdType()).isEqualTo(DeletionHoldType.NONE);
	}

	@Test
	void releaseHoldRestoresFailedWhenPreviousStatusNullAndFailureCodePresent() {
		UUID requestId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, accountId, "e".repeat(64), DeletionRequestStatus.HELD,
				DeletionHoldType.DISPUTE_HOLD, "USER_DISPUTE", 1, 3,
				DeletionEscalationState.NONE, "TIMEOUT_FAILURE", NOW, NOW, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		var released = service.releaseHold(requestId, actorId, UUID.randomUUID());
		assertThat(released.status()).isEqualTo(DeletionRequestStatus.FAILED);
		assertThat(released.holdType()).isEqualTo(DeletionHoldType.NONE);
	}

	@Test
	void applyHoldRejectsCompletedRequestWithConflict() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "c".repeat(64), DeletionRequestStatus.COMPLETED,
				DeletionHoldType.NONE, null, 1, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, NOW, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.applyHold(requestId, DeletionHoldType.LEGAL_HOLD, DeletionHoldReason.STATUTORY_INQUIRY, actorId, UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
	}

	@Test
	void releaseHoldRejectsWhenHoldTypeIsNoneWithBadRequest() {
		UUID requestId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, UUID.randomUUID(), "n".repeat(64), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);
		when(deletionRequests.findById(requestId)).thenReturn(Optional.of(request));

		assertThatThrownBy(() -> service.releaseHold(requestId, actorId, UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(400));
	}

	@Test
	void toDeletionResponseSanitizesRawEmailJournalChatAndSecretToNull() {
		UUID requestId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				requestId, accountId, "a".repeat(64), DeletionRequestStatus.IN_PROGRESS,
				DeletionHoldType.NONE, null, 1, 3,
				DeletionEscalationState.NONE, null, NOW, NOW, null, null, NOW, NOW
		);

		// Fact 1: Raw email leak in evidenceReference and failureReason
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.CARE, DeletionFactStatus.FAILED,
				NOW, "leak.user@example.com", "user@example.com not found", NOW
		));

		// Fact 2: Raw journal text leak
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.JOURNAL_AI, DeletionFactStatus.FAILED,
				NOW, "Hôm nay tôi cảm thấy rất buồn và tuyệt vọng", "Lỗi mã hóa bài viết tâm sự", NOW
		));

		// Fact 3: Raw chat / secret token leak
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.CONSULTATION, DeletionFactStatus.FAILED,
				NOW, "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.supersecret", "secret_key=12345 leaked", NOW
		));

		// Fact 4: Valid opaque audit reference and approved safe failure code
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.COMPLETED,
				NOW, "audit:identity:account-deleted:948f9e80-d3bc-45aa-91f6-044dd5afbf71", "ACCOUNT_NOT_FOUND", NOW
		));

		when(deletionRequests.findAll(any(Specification.class), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(request)));

		var page = service.getDeletionRequests(null, null, 20, 0);
		var facts = page.items().getFirst().ownerFacts();

		// Fact 1: email sanitized to null
		var careFact = facts.stream().filter(f -> f.ownerService() == RetentionOwnerService.CARE).findFirst().orElseThrow();
		assertThat(careFact.evidenceReference()).isNull();
		assertThat(careFact.failureReason()).isNull();

		// Fact 2: journal text sanitized to null
		var journalFact = facts.stream().filter(f -> f.ownerService() == RetentionOwnerService.JOURNAL_AI).findFirst().orElseThrow();
		assertThat(journalFact.evidenceReference()).isNull();
		assertThat(journalFact.failureReason()).isNull();

		// Fact 3: chat secret sanitized to null
		var consultFact = facts.stream().filter(f -> f.ownerService() == RetentionOwnerService.CONSULTATION).findFirst().orElseThrow();
		assertThat(consultFact.evidenceReference()).isNull();
		assertThat(consultFact.failureReason()).isNull();

		// Fact 4: valid audit reference and safe code preserved
		var idFact = facts.stream().filter(f -> f.ownerService() == RetentionOwnerService.IDENTITY).findFirst().orElseThrow();
		assertThat(idFact.evidenceReference()).isEqualTo("audit:identity:account-deleted:948f9e80-d3bc-45aa-91f6-044dd5afbf71");
		assertThat(idFact.failureReason()).isEqualTo("ACCOUNT_NOT_FOUND");
	}

	@Test
	void toDeletionResponseHardensEvidenceReferenceAndFailureCodePrivacyBoundaries() {
		UUID requestId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();

		// Request with raw JWT in failureCode
		var request = new DeletionRequestEntity(
				requestId, accountId, "b".repeat(64), DeletionRequestStatus.FAILED,
				DeletionHoldType.NONE, null, 1, 3,
				DeletionEscalationState.NONE, "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.token.leak",
				NOW, NOW, null, null, NOW, NOW
		);

		// Fact 1: Bare JWT on IDENTITY evidenceReference -> must fail closed to null
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.FAILED,
				NOW, "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.bare.jwt", "AUTH_FAILED", NOW
		));

		// Fact 2: Bare token on IDENTITY evidenceReference -> must fail closed to null
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.FAILED,
				NOW, "opaque_api_key_token_mock_12345", "KEY_EXPIRED", NOW
		));

		// Fact 3: Malformed UUID under the authoritative prefix -> must fail closed to null
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.FAILED,
				NOW, "audit:identity:account-deleted:not-a-uuid", "INVALID_REFERENCE", NOW
		));

		// Fact 4: Secret disguised under the authoritative prefix -> must fail closed to null
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.FAILED,
				NOW, "audit:identity:account-deleted:opaque_secret_key_12345", "INVALID_REFERENCE", NOW
		));

		// Fact 5: Valid producer-backed Identity UUID reference -> preserved
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.COMPLETED,
				NOW, "audit:identity:account-deleted:948f9e80-d3bc-45aa-91f6-044dd5afbf71", null, NOW
		));

		// Fact 6: Legacy format without a production producer -> must fail closed to null
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.COMPLETED,
				NOW, "audit:del:identity:001", null, NOW
		));

		// Fact 7: External owner without an approved evidence contract -> must fail closed to null
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.CARE, DeletionFactStatus.COMPLETED,
				NOW, "audit:identity:account-deleted:948f9e80-d3bc-45aa-91f6-044dd5afbf71", null, NOW
		));

		when(deletionRequests.findAll(any(Specification.class), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(request)));

		var page = service.getDeletionRequests(null, null, 20, 0);
		var response = page.items().getFirst();

		// Request failureCode: bare JWT sanitized to null
		assertThat(response.failureCode()).isNull();

		var facts = response.ownerFacts();
		// Fact 1: bare JWT -> null
		assertThat(facts.get(0).evidenceReference()).isNull();
		// Fact 2: API key -> null
		assertThat(facts.get(1).evidenceReference()).isNull();
		// Fact 3: malformed UUID -> null
		assertThat(facts.get(2).evidenceReference()).isNull();
		// Fact 4: secret hidden behind an audit prefix -> null
		assertThat(facts.get(3).evidenceReference()).isNull();
		// Fact 5: producer-backed Identity UUID reference -> preserved
		assertThat(facts.get(4).evidenceReference()).isEqualTo("audit:identity:account-deleted:948f9e80-d3bc-45aa-91f6-044dd5afbf71");
		// Fact 6: legacy format with no production producer -> null
		assertThat(facts.get(5).evidenceReference()).isNull();
		// Fact 7: external owner evidence without an approved contract -> null
		assertThat(facts.get(6).evidenceReference()).isNull();
	}
}
