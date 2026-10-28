package com.mentalbridge.consultation.earnings;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminPayoutDetailResponse(
		AdminPayoutResponse.Item payout,
		List<AttemptItem> attempts,
		List<EarningItem> earnings,
		List<EventItem> events) {

	public record AttemptItem(
			UUID attemptId,
			int attemptNumber,
			String provider,
			String status,
			String providerReference,
			String failureCode,
			Instant requestedAt,
			Instant confirmedAt,
			Instant failedAt) {
	}

	public record EarningItem(
			UUID earningId,
			UUID appointmentId,
			long amountVnd,
			String status,
			Instant earnedAt,
			Instant paidAt) {
	}

	public record EventItem(
			UUID eventId,
			String provider,
			String eventType,
			String processingStatus,
			String failureCode,
			Instant receivedAt,
			Instant processedAt) {
	}
}
