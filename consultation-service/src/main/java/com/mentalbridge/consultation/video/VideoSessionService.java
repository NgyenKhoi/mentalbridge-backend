package com.mentalbridge.consultation.video;

import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class VideoSessionService {

	private final VideoSessionTransactions transactions;
	private final VideoProvider provider;

	VideoSessionService(VideoSessionTransactions transactions, VideoProvider provider) {
		this.transactions = transactions;
		this.provider = provider;
	}

	public VideoAccessResponse access(UUID id, UUID actor, String role) {
		var room = transactions.prepare(id, actor, role);
		if (room.preparationRequired()) provider.prepareRoom(room.roomName());
		try { return transactions.access(id, actor, role); }
		catch (ApiException exception) {
			if (room.preparationRequired()) {
				transactions.recloseAfterPreparation(id);
				close(id);
			}
			throw exception;
		}
	}

	public VideoSessionResponse session(UUID id, UUID actor, String role) {
		return transactions.session(id, actor, role);
	}

	@Scheduled(fixedDelay = 5000)
	public void closeDueSessions() {
		for (var id : transactions.dueEndIds()) transactions.end(id);
		for (var id : transactions.dueCloseIds()) close(id);
	}

	public void close(UUID id) {
		var closure = transactions.claimClosure(id);
		if (closure == null) return;
		try {
			provider.closeRoom(closure.roomName(), closure.identities(), closure.cutoff());
			transactions.closureResult(id, true);
		}
		catch (ApiException exception) { transactions.closureResult(id, false); }
	}
}
