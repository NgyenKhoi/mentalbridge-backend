package com.mentalbridge.consultation.appointment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AppointmentResponse(UUID id, UUID slotId, UUID specialistAccountId, String specialistDisplayName,
		String status, AppointmentModality modality, Instant scheduledStartAt, Instant scheduledEndAt,
		String timezone, Instant requestedAt, Instant decisionDeadlineAt, UUID heldCreditId,
		UUID replacesAppointmentId, Instant decidedAt, String decisionReason, String creditState, long version) {

	public record ListResponse(List<AppointmentResponse> items, int count, Instant generatedAt) {
	}

	public record BookableSlot(UUID id, UUID specialistAccountId, String specialistDisplayName,
		Instant startAt, Instant endAt, String timezone, AppointmentModality modality) {
	}

	public record BookableSlotList(List<BookableSlot> items, int count, Instant generatedAt,
			boolean videoEnabled) {
	}
}
