package com.mentalbridge.care.consultationbrief;

import java.time.Instant;
import java.util.UUID;

public record AppointmentContext(UUID appointmentId, UUID userAccountId, UUID specialistAccountId,
		String status, Instant scheduledStartAt, Instant scheduledEndAt, long version) {
}
