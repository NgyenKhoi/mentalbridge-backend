package com.mentalbridge.consultation.appointment;

import java.time.Instant;
import java.util.UUID;

public record ChatEvidenceResponse(UUID evidenceId, boolean accepted, boolean duplicate,
		String reasonCode, Instant receivedAt) {
}
