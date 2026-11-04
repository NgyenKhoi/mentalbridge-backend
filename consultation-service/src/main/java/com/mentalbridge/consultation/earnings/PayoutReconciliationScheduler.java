package com.mentalbridge.consultation.earnings;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class PayoutReconciliationScheduler {

	private final PayoutTransactions transactions;
	private final PayoutProvider provider;

	PayoutReconciliationScheduler(PayoutTransactions transactions, PayoutProvider provider) {
		this.transactions = transactions;
		this.provider = provider;
	}

	@Scheduled(fixedDelayString = "${mentalbridge.consultation.payout.reconciliation-interval:PT5S}")
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void reconcile() {
		for (int i = 0; i < 20; i++) {
			var poll = transactions.claimPoll();
			if (poll == null) return;
			if (poll.query() == null) continue;
			PayoutProvider.Result result;
			try {
				result = provider.query(poll.query());
			}
			catch (RuntimeException exception) {
				result = new PayoutProvider.Result("UNKNOWN", poll.query().providerReference(), "PAYOUT_SUBMISSION_UNKNOWN");
			}
			transactions.finishPoll(poll, result);
		}
	}
}
