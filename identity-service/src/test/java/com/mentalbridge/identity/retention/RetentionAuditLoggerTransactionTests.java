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

import com.mentalbridge.identity.account.SecurityAuditEventEntity;
import com.mentalbridge.identity.account.SecurityAuditEventRepository;

@SpringJUnitConfig(RetentionAuditLoggerTransactionTests.TestConfig.class)
class RetentionAuditLoggerTransactionTests {

	@Autowired
	private TestBusinessService businessService;

	@Autowired
	private InMemoryAuditRepository inMemoryRepo;

	@Autowired
	private TrackingTransactionManager txManager;

	@BeforeEach
	void reset() {
		inMemoryRepo.clear();
		txManager.clear();
	}

	@Test
	void deniedAuditPersistsWhenOuterTransactionRollsBackDueToRequiresNew() {
		UUID correlationId = UUID.randomUUID();
		SecurityAuditEventEntity deniedEvent = new SecurityAuditEventEntity(
				UUID.randomUUID(), null, null, "DATA_DELETION_RETRY_REQUESTED", "DENIED", "HOLD_ACTIVE",
				correlationId, null, "IDENTITY", "DATA_RETENTION", Instant.now(), Instant.now()
		);

		assertThatThrownBy(() -> businessService.executeOuterWithDeniedAuditAndRollback(deniedEvent))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Business transaction failed");

		// Outer transaction was rolled back
		assertThat(txManager.outerRolledBack).isTrue();
		// But inner REQUIRES_NEW transaction for denied audit was committed
		assertThat(txManager.requiresNewCommitted).isTrue();
		// And denied audit persisted in repository
		assertThat(inMemoryRepo.findAll()).contains(deniedEvent);
	}

	@Test
	void successAuditDoesNotPersistIfOuterBusinessTransactionRollsBack() {
		UUID correlationId = UUID.randomUUID();
		SecurityAuditEventEntity successEvent = new SecurityAuditEventEntity(
				UUID.randomUUID(), null, null, "DATA_DELETION_RETRY_REQUESTED", "SUCCEEDED", "DELETION_COMPLETED",
				correlationId, null, "IDENTITY", "DATA_RETENTION", Instant.now(), Instant.now()
		);

		assertThatThrownBy(() -> businessService.executeOuterWithSuccessAuditAndRollback(successEvent))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Business transaction failed");

		// Outer transaction rolled back
		assertThat(txManager.outerRolledBack).isTrue();
		// No separate REQUIRES_NEW transaction was created for success audit
		assertThat(txManager.requiresNewCommitted).isFalse();
		// Success audit event rolled back with outer transaction (not retained)
		assertThat(inMemoryRepo.committedEvents).doesNotContain(successEvent);
	}

	@Test
	void successAuditPersistsWhenOuterBusinessTransactionCommits() {
		UUID correlationId = UUID.randomUUID();
		SecurityAuditEventEntity successEvent = new SecurityAuditEventEntity(
				UUID.randomUUID(), null, null, "DATA_DELETION_RETRY_REQUESTED", "SUCCEEDED", "DELETION_COMPLETED",
				correlationId, null, "IDENTITY", "DATA_RETENTION", Instant.now(), Instant.now()
		);

		businessService.executeOuterWithSuccessAuditAndCommit(successEvent);

		// Outer transaction committed
		assertThat(txManager.outerCommitted).isTrue();
		// Success audit event was committed with the transaction
		assertThat(inMemoryRepo.committedEvents).contains(successEvent);
	}

	static class InMemoryAuditRepository {
		final List<SecurityAuditEventEntity> pendingEvents = new ArrayList<>();
		final List<SecurityAuditEventEntity> committedEvents = new ArrayList<>();

		void savePending(SecurityAuditEventEntity entity) {
			pendingEvents.add(entity);
		}

		void commitPending() {
			committedEvents.addAll(pendingEvents);
			pendingEvents.clear();
		}

		void rollbackPending() {
			pendingEvents.clear();
		}

		List<SecurityAuditEventEntity> findAll() {
			List<SecurityAuditEventEntity> all = new ArrayList<>(committedEvents);
			all.addAll(pendingEvents);
			return all;
		}

		void clear() {
			pendingEvents.clear();
			committedEvents.clear();
		}
	}

	static class TrackingTransactionManager extends AbstractPlatformTransactionManager {
		boolean outerCommitted = false;
		boolean outerRolledBack = false;
		boolean requiresNewCommitted = false;

		private final InMemoryAuditRepository repo;
		private final ThreadLocal<TxObj> currentTx = new ThreadLocal<>();

		TrackingTransactionManager(InMemoryAuditRepository repo) {
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
		InMemoryAuditRepository inMemoryRepo() {
			return new InMemoryAuditRepository();
		}

		@Bean
		PlatformTransactionManager transactionManager(InMemoryAuditRepository repo) {
			return new TrackingTransactionManager(repo);
		}

		@Bean
		SecurityAuditEventRepository securityAuditEventRepository(InMemoryAuditRepository repo) {
			SecurityAuditEventRepository mockRepo = mock(SecurityAuditEventRepository.class);
			org.mockito.Mockito.when(mockRepo.save(org.mockito.ArgumentMatchers.any(SecurityAuditEventEntity.class)))
					.thenAnswer(inv -> {
						SecurityAuditEventEntity entity = inv.getArgument(0);
						repo.savePending(entity);
						return entity;
					});
			return mockRepo;
		}

		@Bean
		RetentionAuditLogger retentionAuditLogger(SecurityAuditEventRepository repo) {
			return new RetentionAuditLogger(repo);
		}

		@Bean
		TestBusinessService testBusinessService(RetentionAuditLogger auditLogger) {
			return new TestBusinessService(auditLogger);
		}
	}

	@Service
	static class TestBusinessService {
		private final RetentionAuditLogger auditLogger;

		TestBusinessService(RetentionAuditLogger auditLogger) {
			this.auditLogger = auditLogger;
		}

		@Transactional
		public void executeOuterWithDeniedAuditAndRollback(SecurityAuditEventEntity deniedEvent) {
			// Record denied audit with REQUIRES_NEW propagation
			auditLogger.recordDenied(deniedEvent);
			// Business operation encounters failure, rolling back outer transaction
			throw new IllegalStateException("Business transaction failed");
		}

		@Transactional
		public void executeOuterWithSuccessAuditAndRollback(SecurityAuditEventEntity successEvent) {
			// Record success audit with REQUIRED propagation
			auditLogger.recordSuccess(successEvent);
			// Business operation encounters failure, rolling back outer transaction
			throw new IllegalStateException("Business transaction failed");
		}

		@Transactional
		public void executeOuterWithSuccessAuditAndCommit(SecurityAuditEventEntity successEvent) {
			// Record success audit with REQUIRED propagation
			auditLogger.recordSuccess(successEvent);
			// Business operation succeeds normally
		}
	}
}
