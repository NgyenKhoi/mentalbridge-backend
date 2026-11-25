package com.mentalbridge.consultation.summary;

import java.time.Instant;
import java.util.UUID;

public record ResourceProposalResponse(UUID proposalId, long version, UUID appointmentId,
		UUID userAccountId, UUID specialistAccountId, UUID summaryId, long summaryVersion,
		UUID completionFactId, UUID resourceId, String resourceVersion,
		ResourceProposalReasonCode reasonCode, String title, String details,
		String summarySchemaVersion, Instant proposedAt) {
}
