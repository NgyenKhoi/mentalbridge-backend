package com.mentalbridge.consultation.billing;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionCancellationResponse(UUID subscriptionId, String planCode, String status,
		Instant currentPeriodEnd, Instant cancellationRequestedAt, Instant paidAccessEndedAt,
		UUID liveAppointmentId, Instant liveSessionEndsAt, int cancelledAppointments, int revokedCredits,
		Instant generatedAt) {
}
