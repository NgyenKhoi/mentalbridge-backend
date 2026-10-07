package com.mentalbridge.community.moderation;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonFormat;

public record CommunityAdminAuditEvent(
		UUID eventId,
		String eventType,
		@JsonFormat(shape = JsonFormat.Shape.STRING)
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

