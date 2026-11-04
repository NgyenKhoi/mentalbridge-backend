package com.mentalbridge.consultation.earnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class PayoutReconciliationSchedulerTests {

	@Test
	void providerObservationSuspendsCallingTransactionAndRecordsUncertaintyOnOutage() throws Exception {
		var transactions = mock(PayoutTransactions.class);
		var provider = mock(PayoutProvider.class);
		var poll = poll();
		when(transactions.claimPoll()).thenReturn(poll, null);
		when(provider.query(poll.query())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			throw new IllegalStateException("synthetic provider outage");
		});
		var dataSource = mock(DataSource.class);
		when(dataSource.getConnection()).thenReturn(mock(Connection.class));
		var manager = new DataSourceTransactionManager(dataSource);
		var factory = new ProxyFactory(new PayoutReconciliationScheduler(transactions, provider));
		factory.setProxyTargetClass(true);
		factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
		var scheduler = (PayoutReconciliationScheduler) factory.getProxy();
		new TransactionTemplate(manager).executeWithoutResult(status -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
			scheduler.reconcile();
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
		});
		var observation = ArgumentCaptor.forClass(PayoutProvider.Result.class);
		verify(transactions).finishPoll(eq(poll), observation.capture());
		assertThat(observation.getValue().status()).isEqualTo("UNKNOWN");
		assertThat(observation.getValue().providerReference()).isEqualTo("outer-existing");
		assertThat(observation.getValue().failureCode()).isEqualTo("PAYOUT_SUBMISSION_UNKNOWN");
	}

	@Test
	void eachInvocationIsBoundedAndOverdueClaimsMakeNoProviderCall() {
		var transactions = mock(PayoutTransactions.class);
		var provider = mock(PayoutProvider.class);
		when(transactions.claimPoll()).thenReturn(new PayoutTransactions.Poll(null, null, null));
		new PayoutReconciliationScheduler(transactions, provider).reconcile();
		verify(transactions, times(20)).claimPoll();
		verifyNoInteractions(provider);
		org.mockito.Mockito.verify(transactions, org.mockito.Mockito.never()).finishPoll(any(), any());
	}

	private PayoutTransactions.Poll poll() {
		var query = new PayoutProvider.Query("PAYOS", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(),
				210_000, "VND", "BANK_ACCOUNT", "unused-encrypted-snapshot", "outer-existing");
		return new PayoutTransactions.Poll(query.payoutId(), UUID.randomUUID(), query);
	}
}
