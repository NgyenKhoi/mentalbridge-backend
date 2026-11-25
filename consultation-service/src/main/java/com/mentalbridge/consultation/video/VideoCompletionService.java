package com.mentalbridge.consultation.video;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class VideoCompletionService {

	private final VideoCompletionTransactions transactions;
	private final VideoSessionService sessions;

	VideoCompletionService(VideoCompletionTransactions transactions, VideoSessionService sessions) {
		this.transactions = transactions;
		this.sessions = sessions;
	}

	Result confirm(UUID id, UUID actor, String role, long version, String key) {
		boolean replay;
		try { replay = transactions.confirm(id, actor, role, version, key); }
		catch (DataIntegrityViolationException exception) { replay = transactions.confirm(id, actor, role, version, key); }
		sessions.close(id);
		transactions.reconcile(id);
		return new Result(transactions.response(id, actor, role), replay);
	}

	@Scheduled(fixedDelay = 5000)
	void reconcilePending() { for (var id : transactions.pendingIds()) transactions.reconcile(id); }

	record Result(VideoCompletionResponse response, boolean replay) { }
}
