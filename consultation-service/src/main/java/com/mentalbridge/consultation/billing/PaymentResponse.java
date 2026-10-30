package com.mentalbridge.consultation.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentResponse(UUID paymentId, UUID subscriptionId, UUID planVersionId, String planCode,
		String planVersion, String pricingPolicyVersion, String purpose, String provider, String status, long amountVnd, String currency,
		String checkoutUrl, String qrCodeUrl, Instant expiresAt, Instant createdAt, Instant paidAt,
		Instant failedAt, String qrCode, String bin, String accountNumber, String accountName,
        String description, Long amount, Instant expiredAt, String terminalReason) {

	public record History(List<PaymentResponse> items, int count, Instant generatedAt) { }
}
