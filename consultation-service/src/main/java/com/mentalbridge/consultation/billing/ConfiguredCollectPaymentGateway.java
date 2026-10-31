package com.mentalbridge.consultation.billing;

import java.time.Instant;

import org.springframework.stereotype.Component;

@Component
class ConfiguredCollectPaymentGateway implements CollectPaymentGateway {

	private final BillingProperties properties;
	private final MomoCheckoutGateway momo;
	private final PayosCheckoutGateway payos;

	ConfiguredCollectPaymentGateway(BillingProperties properties, MomoCheckoutGateway momo, PayosCheckoutGateway payos) {
		this.properties = properties;
		this.momo = momo;
		this.payos = payos;
	}

	@Override
	public String provider() {
		return switch (properties.getMode()) {
			case "FAKE" -> "FAKE";
			case "MOMO" -> "MOMO";
			default -> "PAYOS";
		};
	}

	@Override
	public Checkout create(String orderId, String requestId, Long orderCode, long amount, Instant expiresAt) {
		if (properties.fake()) {
			var description = "MB" + String.format(java.util.Locale.ROOT, "%07d", orderCode % 10_000_000L);
			var payload = "MENTALBRIDGE:FAKE?bin=000000&account=SIMULATED&amount=" + amount
					+ "&description=" + description + "&expiredAt=" + expiresAt.getEpochSecond();
			return new Checkout(null, null, null, payload, "000000", "SIMULATED",
					"MentalBridge simulation", description);
		}
		if ("PAYOS".equals(properties.getMode())) return payos.create(orderCode, amount, expiresAt);
		var checkout = momo.create(orderId, requestId, amount, "MentalBridge subscription");
		return new Checkout(checkout.payUrl(), checkout.qrCodeUrl(), null, null, null, null, null, null);
	}
}
