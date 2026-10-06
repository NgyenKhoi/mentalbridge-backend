package com.mentalbridge.consultation.earnings;

import java.util.UUID;

interface PayoutProvider {

	Result submit(Command command);

	record Command(UUID payoutId, UUID attemptId, String requestId, long amountVnd, String currency,
			String destinationType, String destinationCiphertext) {
	}

	record Result(String status, String providerReference, String failureCode) {
	}
}
