package com.mentalbridge.care.supportplan;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JournalEntryReconciliationService {
	private final JournalEntryReconciliationWriter writer;
	private final JournalEntryEvidenceClient evidence;

	public JournalEntryReconciliationService(JournalEntryReconciliationWriter writer,
			JournalEntryEvidenceClient evidence) {
		this.writer = writer;
		this.evidence = evidence;
	}

	@Transactional(propagation = Propagation.NEVER)
	public JournalEntryReconciliationWriter.Reconciliation reconcile(UUID userId, UUID occurrenceId, UUID journalId,
			String bearerToken, UUID correlationId) {
		UUID correlation = correlationId == null ? UUID.randomUUID() : correlationId;
		var context = writer.context(userId, occurrenceId);
		var fact = evidence.read(userId, journalId, bearerToken, correlation);
		return writer.accept(userId, context, journalId, fact, correlation);
	}
}
