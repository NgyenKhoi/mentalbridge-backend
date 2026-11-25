package com.mentalbridge.care.supportplan;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmotionCheckInReconciliationService {
	private final EmotionCheckInReconciliationWriter writer;
	private final EmotionCheckInEvidenceClient evidence;

	public EmotionCheckInReconciliationService(EmotionCheckInReconciliationWriter writer,
			EmotionCheckInEvidenceClient evidence) {
		this.writer = writer;
		this.evidence = evidence;
	}

	@Transactional(propagation = Propagation.NEVER)
	public EmotionCheckInReconciliationWriter.Reconciliation reconcile(UUID userId, UUID occurrenceId,
			String bearerToken, UUID correlationId) {
		UUID correlation = correlationId == null ? UUID.randomUUID() : correlationId;
		var context = writer.context(userId, occurrenceId);
		var fact = evidence.read(userId, context.localDate(), bearerToken, correlation);
		return writer.accept(userId, context, fact, correlation);
	}
}
