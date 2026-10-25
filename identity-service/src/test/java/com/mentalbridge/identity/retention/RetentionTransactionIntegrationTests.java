package com.mentalbridge.identity.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.account.AccountEntity;
import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.account.SecurityAuditEventEntity;

@org.junit.jupiter.api.condition.EnabledIf("dockerAvailable")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RetentionTransactionIntegrationTests extends IdentityTestProperties {

	@Autowired
	private RetentionExecutionRecorder executionRecorder;

	@Autowired
	private RetentionAuditLogger auditLogger;

	@Autowired
	private DeletionRequestRepository deletionRequests;

	@Autowired
	private AccountRepository accounts;

	@Autowired
	private AdminRetentionGovernanceService governanceService;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private JdbcClient jdbc;

	private UUID executionId;
	private UUID auditId;
	private UUID deletionRequestId;
	private UUID accountId;

	@AfterEach
	void cleanUp() {
		if (executionId != null) {
			jdbc.sql("delete from retention_execution_record where id = :id")
					.param("id", executionId)
					.update();
		}
		if (auditId != null) {
			jdbc.sql("delete from security_audit_event where id = :id")
					.param("id", auditId)
					.update();
		}
		if (deletionRequestId != null) {
			jdbc.sql("delete from deletion_request where id = :id")
					.param("id", deletionRequestId)
					.update();
		}
		if (accountId != null) {
			jdbc.sql("delete from account where id = :id")
					.param("id", accountId)
					.update();
		}
	}

	@Test
	void retryDeletionCommitsOwnerEvidenceLinkedToTheSuccessAuditEvent() {
		Instant now = Instant.now();
		var account = AccountEntity.pending(
				"mb673-" + UUID.randomUUID() + "@synthetic.invalid", "test-password-hash", RoleCode.USER, now);
		account.activate(now);
		accountId = account.id();
		deletionRequestId = UUID.randomUUID();
		var request = new DeletionRequestEntity(
				deletionRequestId, accountId, "a".repeat(64), DeletionRequestStatus.PENDING,
				DeletionHoldType.NONE, null, 0, 3, DeletionEscalationState.NONE, null,
				now, now.minusSeconds(1), null, null, now, now);
		request.addOwnerFact(new DeletionOwnerFactEntity(
				UUID.randomUUID(), request, RetentionOwnerService.IDENTITY, DeletionFactStatus.PENDING,
				null, null, null, now));

		transactionTemplate.executeWithoutResult(tx -> {
			accounts.save(account);
			deletionRequests.save(request);
		});

		governanceService.retryDeletion(deletionRequestId, null, UUID.randomUUID());

		String evidenceReference = jdbc.sql("""
				select evidence_reference
				from deletion_owner_fact
				where deletion_request_id = :requestId and owner_service = 'IDENTITY'
				""")
				.param("requestId", deletionRequestId)
				.query(String.class)
				.single();
		auditId = UUID.fromString(evidenceReference.substring("audit:identity:account-deleted:".length()));

		assertThat(auditId).isNotEqualTo(deletionRequestId);
		assertThat(jdbc.sql("""
				select count(*)
				from security_audit_event
				where id = :auditId
				  and action = 'DATA_DELETION_RETRY_REQUESTED'
				  and outcome = 'SUCCEEDED'
				""")
				.param("auditId", auditId)
				.query(Long.class)
				.single()).isOne();
	}

	@Test
	void holdRestoresExactPreviousStatusAfterCommitAndReload() {
		for (DeletionRequestStatus previous : List.of(
				DeletionRequestStatus.FAILED, DeletionRequestStatus.PENDING, DeletionRequestStatus.IN_PROGRESS)) {
			deletionRequestId = UUID.randomUUID();
			UUID id = deletionRequestId;
			int retryCount = previous == DeletionRequestStatus.PENDING ? 1 : 0;
			Instant now = Instant.now();
			var request = new DeletionRequestEntity(id, UUID.randomUUID(), "a".repeat(64), previous,
					DeletionHoldType.NONE, null, retryCount, 3, DeletionEscalationState.NONE, null,
					now, now, null, null, now, now);
			transactionTemplate.executeWithoutResult(tx -> deletionRequests.save(request));
			transactionTemplate.executeWithoutResult(tx ->
					deletionRequests.findById(id).orElseThrow().applyHold(
							DeletionHoldType.LEGAL_HOLD, "STATUTORY_INQUIRY", Instant.now()));

			assertThat(jdbc.sql("select previous_status from deletion_request where id = :id")
					.param("id", id).query(String.class).single()).isEqualTo(previous.name());
			transactionTemplate.executeWithoutResult(tx -> {
				var reloaded = deletionRequests.findById(id).orElseThrow();
				assertThat(reloaded.status()).isEqualTo(DeletionRequestStatus.HELD);
				reloaded.releaseHold(Instant.now());
			});
			assertThat(jdbc.sql("select status from deletion_request where id = :id")
					.param("id", id).query(String.class).single()).isEqualTo(previous.name());
			jdbc.sql("delete from deletion_request where id = :id").param("id", id).update();
			deletionRequestId = null;
		}
	}

	@Test
	void failedExecutionPersistsWhenOuterDatabaseTransactionRollsBack() {
		executionId = UUID.randomUUID();
		var record = executionRecord(executionId, RetentionJobStatus.FAILED.name(), "SWEEP_EXECUTION_FAILED");

		assertThatThrownBy(() -> rollBackAfter(() -> executionRecorder.recordFailure(record)))
				.isInstanceOf(ExpectedRollback.class);

		assertThat(executionCount(executionId)).isOne();
	}

	@Test
	void successfulExecutionDoesNotPersistWhenOuterDatabaseTransactionRollsBack() {
		executionId = UUID.randomUUID();
		var record = executionRecord(executionId, RetentionJobStatus.SUCCEEDED.name(), null);

		assertThatThrownBy(() -> rollBackAfter(() -> executionRecorder.recordSuccess(record)))
				.isInstanceOf(ExpectedRollback.class);

		assertThat(executionCount(executionId)).isZero();
	}

	@Test
	void deniedAuditPersistsWhenOuterDatabaseTransactionRollsBack() {
		auditId = UUID.randomUUID();
		var event = auditEvent(auditId, "DENIED");

		assertThatThrownBy(() -> rollBackAfter(() -> auditLogger.recordDenied(event)))
				.isInstanceOf(ExpectedRollback.class);

		assertThat(auditCount(auditId)).isOne();
	}

	@Test
	void successfulAuditDoesNotPersistWhenOuterDatabaseTransactionRollsBack() {
		auditId = UUID.randomUUID();
		var event = auditEvent(auditId, "SUCCEEDED");

		assertThatThrownBy(() -> rollBackAfter(() -> auditLogger.recordSuccess(event)))
				.isInstanceOf(ExpectedRollback.class);

		assertThat(auditCount(auditId)).isZero();
	}

	private void rollBackAfter(Runnable action) {
		transactionTemplate.executeWithoutResult(status -> {
			action.run();
			throw new ExpectedRollback();
		});
	}

	private RetentionExecutionRecordEntity executionRecord(UUID id, String status, String failureCode) {
		Instant now = Instant.now();
		return new RetentionExecutionRecordEntity(
				id, "MB-IDENTITY-REPORTING-001", "IDENTITY", "REPORT_ARTIFACT_RETENTION_SWEEP",
				status, now, now, 1, 0, 0, 0, failureCode, now
		);
	}

	private SecurityAuditEventEntity auditEvent(UUID id, String outcome) {
		Instant now = Instant.now();
		return new SecurityAuditEventEntity(
				id, null, null, "RETENTION_SWEEP_TRIGGERED", outcome, "TEST_ROLLBACK",
				UUID.randomUUID(), null, "IDENTITY", "DATA_RETENTION", now, now
		);
	}

	private long executionCount(UUID id) {
		return jdbc.sql("select count(*) from retention_execution_record where id = :id")
				.param("id", id)
				.query(Long.class)
				.single();
	}

	private long auditCount(UUID id) {
		return jdbc.sql("select count(*) from security_audit_event where id = :id")
				.param("id", id)
				.query(Long.class)
				.single();
	}

	private static final class ExpectedRollback extends RuntimeException {
	}

	static boolean dockerAvailable() {
		try {
			var process = new ProcessBuilder("docker", "info").start();
			return process.waitFor() == 0;
		} catch (Exception e) {
			return false;
		}
	}
}
