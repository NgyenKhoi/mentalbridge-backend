package com.mentalbridge.identity.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@SpringJUnitConfig(RetentionExecutionRecorderTransactionTests.TestConfig.class)
class RetentionExecutionRecorderTransactionTests {

	@Autowired
	private TestBusinessExecutionService businessService;

	@Autowired
	private InMemoryExecutionRecordRepository inMemoryRepo;

	@Autowired
	private TrackingTransactionManager txManager;

	@BeforeEach
	void reset() {
		inMemoryRepo.clear();
		txManager.clear();
	}

	@Test
	void failureRecordPersistsWhenOuterTransactionRollsBackDueToRequiresNew() {
		RetentionExecutionRecordEntity failureRecord = new RetentionExecutionRecordEntity(
				UUID.randomUUID(), "MB-IDENTITY-REPORTING-001", "IDENTITY", "REPORT_ARTIFACT_RETENTION_SWEEP",
				"FAILED", Instant.now(), Instant.now(), 10, 0, 0, 0, "SWEEP_EXECUTION_FAILED", Instant.now()
		);

		assertThatThrownBy(() -> businessService.executeOuterWithFailureRecordAndRollback(failureRecord))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Business sweep transaction failed");

		// Outer business transaction was rolled back
		assertThat(txManager.outerRolledBack).isTrue();
		// But inner REQUIRES_NEW transaction for failure record was committed
		assertThat(txManager.requiresNewCommitted).isTrue();
		// And failure record persisted in repository
		assertThat(inMemoryRepo.findAll()).contains(failureRecord);
	}

	@Test
	void successRecordDoesNotPersistIfOuterBusinessTransactionRollsBack() {
		RetentionExecutionRecordEntity successRecord = new RetentionExecutionRecordEntity(
				UUID.randomUUID(), "MB-IDENTITY-REPORTING-001", "IDENTITY", "REPORT_ARTIFACT_RETENTION_SWEEP",
				"SUCCEEDED", Instant.now(), Instant.now(), 50, 12, 0, 0, null, Instant.now()
		);

		assertThatThrownBy(() -> businessService.executeOuterWithSuccessRecordAndRollback(successRecord))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Business sweep transaction failed");

		// Outer transaction rolled back
		assertThat(txManager.outerRolledBack).isTrue();
		// No separate REQUIRES_NEW transaction was created for success record
		assertThat(txManager.requiresNewCommitted).isFalse();
		// Success record was rolled back with outer transaction
		assertThat(inMemoryRepo.committedRecords).doesNotContain(successRecord);
	}

	@Test
	void successRecordPersistsWhenOuterBusinessTransactionCommits() {
		RetentionExecutionRecordEntity successRecord = new RetentionExecutionRecordEntity(
				UUID.randomUUID(), "MB-IDENTITY-REPORTING-001", "IDENTITY", "REPORT_ARTIFACT_RETENTION_SWEEP",
				"SUCCEEDED", Instant.now(), Instant.now(), 50, 12, 0, 0, null, Instant.now()
		);

		businessService.executeOuterWithSuccessRecordAndCommit(successRecord);

		// Outer transaction committed
		assertThat(txManager.outerCommitted).isTrue();
		// Success execution record was committed with the transaction
		assertThat(inMemoryRepo.committedRecords).contains(successRecord);
	}

	static class InMemoryExecutionRecordRepository {
		final List<RetentionExecutionRecordEntity> pendingRecords = new ArrayList<>();
		final List<RetentionExecutionRecordEntity> committedRecords = new ArrayList<>();

		void savePending(RetentionExecutionRecordEntity entity) {
			pendingRecords.add(entity);
		}

		void commitPending() {
			committedRecords.addAll(pendingRecords);
			pendingRecords.clear();
		}

		void rollbackPending() {
			pendingRecords.clear();
		}

		List<RetentionExecutionRecordEntity> findAll() {
			List<RetentionExecutionRecordEntity> all = new ArrayList<>(committedRecords);
			all.addAll(pendingRecords);
			return all;
		}

		void clear() {
			pendingRecords.clear();
			committedRecords.clear();
		}
	}

	static class TrackingTransactionManager extends AbstractPlatformTransactionManager {
		boolean outerCommitted = false;
		boolean outerRolledBack = false;
		boolean requiresNewCommitted = false;

		private final InMemoryExecutionRecordRepository repo;
		private final ThreadLocal<TxObj> currentTx = new ThreadLocal<>();

		TrackingTransactionManager(InMemoryExecutionRecordRepository repo) {
			this.repo = repo;
		}

		void clear() {
			outerCommitted = false;
			outerRolledBack = false;
			requiresNewCommitted = false;
			currentTx.remove();
		}

		static class TxObj {
			boolean active = false;
		}

		@Override
		protected Object doGetTransaction() throws TransactionException {
			TxObj tx = currentTx.get();
			return tx != null ? tx : new TxObj();
		}

		@Override
		protected boolean isExistingTransaction(Object transaction) {
			return ((TxObj) transaction).active;
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
			TxObj tx = (TxObj) transaction;
			tx.active = true;
			currentTx.set(tx);
		}

		@Override
		protected Object doSuspend(Object transaction) throws TransactionException {
			TxObj tx = (TxObj) transaction;
			tx.active = false;
			currentTx.remove();
			return tx;
		}

		@Override
		protected void doResume(Object transaction, Object suspendedResources) throws TransactionException {
			TxObj tx = (TxObj) transaction;
			tx.active = true;
			currentTx.set(tx);
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
			TxObj tx = (TxObj) status.getTransaction();
			tx.active = false;
			if (status.isNewTransaction()) {
				currentTx.remove();
				if (status.getSuspendedResources() != null) {
					requiresNewCommitted = true;
				} else {
					outerCommitted = true;
				}
				repo.commitPending();
			}
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
			TxObj tx = (TxObj) status.getTransaction();
			tx.active = false;
			if (status.isNewTransaction()) {
				currentTx.remove();
				outerRolledBack = true;
				repo.rollbackPending();
			}
		}
	}

	@Configuration
	@EnableTransactionManagement
	static class TestConfig {

		@Bean
		InMemoryExecutionRecordRepository inMemoryRepo() {
			return new InMemoryExecutionRecordRepository();
		}

		@Bean
		PlatformTransactionManager transactionManager(InMemoryExecutionRecordRepository repo) {
			return new TrackingTransactionManager(repo);
		}

		@Bean
		RetentionExecutionRecordRepository retentionExecutionRecordRepository(InMemoryExecutionRecordRepository repo) {
			RetentionExecutionRecordRepository mockRepo = mock(RetentionExecutionRecordRepository.class);
			org.mockito.Mockito.when(mockRepo.save(org.mockito.ArgumentMatchers.any(RetentionExecutionRecordEntity.class)))
					.thenAnswer(inv -> {
						RetentionExecutionRecordEntity entity = inv.getArgument(0);
						repo.savePending(entity);
						return entity;
					});
			return mockRepo;
		}

		@Bean
		RetentionExecutionRecorder retentionExecutionRecorder(RetentionExecutionRecordRepository repo) {
			return new RetentionExecutionRecorder(repo);
		}

		@Bean
		TestBusinessExecutionService testBusinessExecutionService(RetentionExecutionRecorder recorder) {
			return new TestBusinessExecutionService(recorder);
		}
	}

	@Service
	static class TestBusinessExecutionService {
		private final RetentionExecutionRecorder recorder;

		TestBusinessExecutionService(RetentionExecutionRecorder recorder) {
			this.recorder = recorder;
		}

		@Transactional
		public void executeOuterWithFailureRecordAndRollback(RetentionExecutionRecordEntity failureRecord) {
			// Record failure with REQUIRES_NEW propagation
			recorder.recordFailure(failureRecord);
			// Business sweep encounters failure, rolling back outer transaction
			throw new IllegalStateException("Business sweep transaction failed");
		}

		@Transactional
		public void executeOuterWithSuccessRecordAndRollback(RetentionExecutionRecordEntity successRecord) {
			// Record success with REQUIRED propagation
			recorder.recordSuccess(successRecord);
			// Business sweep encounters failure, rolling back outer transaction
			throw new IllegalStateException("Business sweep transaction failed");
		}

		@Transactional
		public void executeOuterWithSuccessRecordAndCommit(RetentionExecutionRecordEntity successRecord) {
			// Record success with REQUIRED propagation
			recorder.recordSuccess(successRecord);
			// Business sweep succeeds normally
		}
	}
}

