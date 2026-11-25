package com.mentalbridge.consultation.earnings;

import org.springframework.stereotype.Component;

@Component
class ConfiguredPayoutProvider implements PayoutProvider {

	private final PayoutProperties properties;
	private final MomoPayoutProvider momo;
	private final PayosPayoutProvider payos;

	ConfiguredPayoutProvider(PayoutProperties properties, MomoPayoutProvider momo, PayosPayoutProvider payos) {
		this.properties = properties;
		this.momo = momo;
		this.payos = payos;
	}

	@Override
	public Result submit(Command command) {
		return submit(properties.getMode(), command);
	}

	@Override
	public Result submit(String provider, Command command) {
		if (!properties.submitReady(provider)) return new Result("FAILED", null, "REAL_PAYOUT_DISABLED");
		return switch (provider) {
			case "MOMO" -> momo.submit(command);
			case "PAYOS" -> payos.submit(command);
			case "FAKE" -> new Result("SUCCEEDED", "fake-" + command.payoutId(), null);
			default -> new Result("FAILED", null, "REAL_PAYOUT_DISABLED");
		};
	}

	@Override
	public Result query(Query query) {
		if (!properties.verificationReady(query.provider())) {
			return new Result("UNKNOWN", query.providerReference(), "PAYOUT_PROVIDER_UNAVAILABLE");
		}
		return switch (query.provider()) {
			case "PAYOS" -> payos.query(query);
			case "FAKE" -> new Result("SUCCEEDED", "fake-" + query.payoutId(), null);
			default -> new Result("UNKNOWN", query.providerReference(), "PAYOUT_SUBMISSION_UNKNOWN");
		};
	}
}
