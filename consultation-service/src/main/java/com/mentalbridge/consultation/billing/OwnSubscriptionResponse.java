package com.mentalbridge.consultation.billing;

import java.time.Instant;
import java.util.UUID;

public record OwnSubscriptionResponse(UUID subscriptionId, String planCode, String status,
		Instant currentPeriodStart, Instant currentPeriodEnd, Instant cancellationRequestedAt,
		Instant paidAccessEndedAt, UUID liveAppointmentId, Instant liveSessionEndsAt, Instant generatedAt) {
}
