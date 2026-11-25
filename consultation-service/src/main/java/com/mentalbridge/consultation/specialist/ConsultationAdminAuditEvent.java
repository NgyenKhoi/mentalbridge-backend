package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.UUID;

public record ConsultationAdminAuditEvent(
		UUID eventId,
		String eventType,
		@com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
		Instant occurredAt,
		String producer,
		String schemaVersion,
		String sourceService,
		String domain,
		UUID actorId,
		String actorType,
		String action,
		String result,
		String reasonCode,
		UUID correlationId,
		UUID targetAccountId,
		String targetIdentifier
) {
}
