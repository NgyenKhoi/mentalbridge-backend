package com.mentalbridge.identity.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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
	private TransactionTemplate transactionTemplate;

	@Autowired
	private JdbcClient jdbc;

	private UUID executionId;
	private UUID auditId;

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
