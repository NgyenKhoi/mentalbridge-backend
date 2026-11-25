package com.mentalbridge.care.supportplan;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceReconciliationService {
	private final ResourceReconciliationWriter writer;
	private final ResourceEvidenceClient evidence;

	public ResourceReconciliationService(ResourceReconciliationWriter writer, ResourceEvidenceClient evidence) {
		this.writer = writer;
		this.evidence = evidence;
	}

	@Transactional(propagation = Propagation.NEVER)
	public SupportPlanV3Service.ActionOccurrenceView reconcile(UUID userId, UUID occurrenceId, long version,
			String bearerToken, UUID correlationId) {
		UUID correlation = correlationId == null ? UUID.randomUUID() : correlationId;
		var context = writer.context(userId, occurrenceId, version);
		if (context.replay() != null) return context.replay();
		var usedFactIds = writer.usedFactIds(userId, context);
		var fact = evidence.read(UUID.fromString(context.source().sourceId()), context.source().sourceRevision(),
				context.localDate(), context.timeZone(), usedFactIds, bearerToken, correlation);
		if (fact == null && !usedFactIds.isEmpty()) {
			fact = evidence.read(UUID.fromString(context.source().sourceId()), context.source().sourceRevision(),
					context.localDate(), context.timeZone(), java.util.List.of(), bearerToken, correlation);
		}
		return writer.accept(userId, context, fact, correlation);
	}
}
