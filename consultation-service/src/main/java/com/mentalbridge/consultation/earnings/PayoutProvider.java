package com.mentalbridge.consultation.earnings;

import java.util.UUID;

interface PayoutProvider {

	Result submit(UUID payoutId, UUID attemptId, long amountVnd, String currency);

	record Result(String status, String providerReference, String failureCode) {
	}
}
