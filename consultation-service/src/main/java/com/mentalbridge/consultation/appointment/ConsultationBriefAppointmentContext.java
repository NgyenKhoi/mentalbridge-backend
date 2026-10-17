package com.mentalbridge.consultation.appointment;

import java.time.Instant;
import java.util.UUID;

public record ConsultationBriefAppointmentContext(UUID appointmentId, UUID userAccountId,
		UUID specialistAccountId, String status, Instant scheduledStartAt, Instant scheduledEndAt,
		long version) {
}
