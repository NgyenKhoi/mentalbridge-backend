package com.mentalbridge.consultation.earnings;

import java.util.UUID;

interface PayoutProvider {

	Result submit(Command command);

	default Result submit(String provider, Command command) {
		return submit(command);
	}

	default Result query(Query query) {
		return new Result("UNKNOWN", query.providerReference(), "PAYOUT_SUBMISSION_UNKNOWN");
	}

	record Command(UUID payoutId, UUID attemptId, String requestId, long amountVnd, String currency,
			String destinationType, String destinationCiphertext) {
	}

	record Query(String provider, UUID payoutId, UUID attemptId, String requestId, long amountVnd, String currency,
			String destinationType, String destinationCiphertext, String providerReference) {
	}

	record Result(String status, String providerReference, String failureCode, String childReference) {
		Result(String status, String providerReference, String failureCode) {
			this(status, providerReference, failureCode, null);
		}
	}
}
