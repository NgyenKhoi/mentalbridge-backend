package com.mentalbridge.consultation.rating;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AppointmentRatingResponse(UUID appointmentId, UUID specialistAccountId, int rating,
		Instant createdAt, Instant updatedAt, long version, SpecialistAggregate specialistAggregate) {

	public record SpecialistAggregate(BigDecimal averageRating, long ratingCount) { }
}
