package com.mentalbridge.consultation.earnings;

import org.springframework.stereotype.Component;

@Component
class ConfiguredPayoutProvider implements PayoutProvider {

	private final PayoutProperties properties;
	private final MomoPayoutProvider momo;

	ConfiguredPayoutProvider(PayoutProperties properties, MomoPayoutProvider momo) {
		this.properties = properties;
		this.momo = momo;
	}

	@Override
	public Result submit(Command command) {
		if (properties.getMode().equals("MOMO")) {
			if (properties.momoReady()) return momo.submit(command);
			return new Result("FAILED", null, "REAL_PAYOUT_DISABLED");
		}
		return new Result("SUCCEEDED", "fake-" + command.payoutId(), null);
	}
}
