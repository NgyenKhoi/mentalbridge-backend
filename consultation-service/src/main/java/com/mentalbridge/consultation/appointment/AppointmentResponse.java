package com.mentalbridge.consultation.appointment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AppointmentResponse(UUID id, UUID slotId, UUID specialistAccountId, String specialistDisplayName,
		String status, AppointmentModality modality, Instant scheduledStartAt, Instant scheduledEndAt,
		String timezone, Instant requestedAt, Instant decisionDeadlineAt, UUID heldCreditId,
		UUID replacesAppointmentId, UUID replacedByAppointmentId, Instant decidedAt, String decisionReason,
		Instant cancelledAt, String cancellationReason, String cancellationActor,
		String cancellationCreditOutcome, String creditState, List<HistoryEntry> history, long version) {

	AppointmentResponse withHistory(List<HistoryEntry> value) {
		return new AppointmentResponse(id, slotId, specialistAccountId, specialistDisplayName, status, modality,
				scheduledStartAt, scheduledEndAt, timezone, requestedAt, decisionDeadlineAt, heldCreditId,
				replacesAppointmentId, replacedByAppointmentId, decidedAt, decisionReason, cancelledAt,
				cancellationReason, cancellationActor, cancellationCreditOutcome, creditState, value, version);
	}

	public record HistoryEntry(UUID eventId, String fromStatus, String toStatus, String actorType,
			UUID actorId, String reason, String creditOutcome, Instant occurredAt) {
	}

	public record ListResponse(List<AppointmentResponse> items, int count, Instant generatedAt) {
	}

	public record BookableSlot(UUID id, UUID specialistAccountId, String specialistDisplayName,
		Instant startAt, Instant endAt, String timezone, AppointmentModality modality) {
	}

	public record BookableSlotList(List<BookableSlot> items, int count, Instant generatedAt,
			boolean videoEnabled) {
	}
}
