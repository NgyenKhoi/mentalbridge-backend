package com.mentalbridge.consultation.earnings;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class FakePayoutProvider implements PayoutProvider {

	private final PayoutProperties properties;

	FakePayoutProvider(PayoutProperties properties) {
		this.properties = properties;
	}

	@Override
	public Result submit(UUID payoutId, UUID attemptId, long amountVnd, String currency) {
		if (!properties.getMode().equals("FAKE")) {
			return new Result("FAILED", null, "REAL_PAYOUT_DISABLED");
		}
		return new Result("SUCCEEDED", "fake-" + payoutId, null);
	}
}
