package com.mentalbridge.consultation.earnings;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminPayoutResponse(Instant generatedAt, int count, List<Item> items) {

	public record Item(UUID payoutId, UUID specialistAccountId, String destinationHint, long amountVnd,
			String currency, String provider, String status, int earningCount, String providerReference,
			String failureCode, Instant requestedAt, Instant completedAt) {
	}
}
