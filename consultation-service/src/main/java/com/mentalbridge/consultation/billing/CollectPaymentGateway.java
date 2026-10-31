package com.mentalbridge.consultation.billing;

import java.time.Instant;

interface CollectPaymentGateway {

	String provider();

	Checkout create(String orderId, String requestId, Long orderCode, long amount, Instant expiresAt);

	record Checkout(String checkoutUrl, String qrCodeUrl, String paymentLinkId, String qrCode,
			String bin, String accountNumber, String accountName, String description) { }
}
