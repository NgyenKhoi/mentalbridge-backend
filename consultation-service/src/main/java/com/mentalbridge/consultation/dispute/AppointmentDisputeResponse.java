package com.mentalbridge.consultation.dispute;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AppointmentDisputeResponse(UUID id, UUID appointmentId, long appointmentVersion,
		String status, String openedByRole, String reasonCode, String evidenceType,
		Instant evidenceOccurredAt, Instant openedAt, Instant eligibleUntil, boolean settlementGated,
		String resolutionOutcome, String resolutionReason, Instant resolvedAt,
		String priorAppointmentStatus, String priorSessionOutcome,
		String resultingAppointmentStatus, String resultingSessionOutcome,
		String creditAction, long version) {

	public record ListResponse(List<AppointmentDisputeResponse> items, int count, Instant generatedAt) { }
}
