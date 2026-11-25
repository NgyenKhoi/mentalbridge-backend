package com.mentalbridge.consultation.appointment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminAppointmentResponse {

	private AdminAppointmentResponse() {
	}

	public record Item(UUID appointmentId, UUID availabilitySlotId, UUID userAccountId,
			UUID specialistAccountId, String status, AppointmentModality modality,
			Instant scheduledStartAt, Instant scheduledEndAt, String timezone, Instant requestedAt,
			Instant decisionDeadlineAt, Instant decidedAt, String decisionReasonCode, Instant cancelledAt,
			String cancellationReasonCode, String cancellationCreditOutcome, Instant sessionEndedAt,
			Instant sessionSettledAt, String sessionOutcome, String sessionOutcomeReasonCode,
			String settlementState, Instant updatedAt, long version) {
	}

	public record Page(String source, String dataState, Instant generatedAt, Instant queryFrom,
			Instant queryTo, List<Item> items, int count, String nextCursor) {
		public Page {
			items = List.copyOf(items);
		}
	}
}
