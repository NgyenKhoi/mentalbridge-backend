package com.mentalbridge.consultation.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminPaymentResponse(Instant generatedAt, int count, List<Item> items) {

	public record Item(UUID paymentId, UUID subscriptionId, UUID planVersionId, String planCode,
			String pricingPolicyVersion, String purpose, String provider, String status, long amountVnd,
			String currency, Instant createdAt, Instant paidAt, Instant failedAt) { }
}
