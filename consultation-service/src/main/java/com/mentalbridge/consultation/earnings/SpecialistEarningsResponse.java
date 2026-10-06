package com.mentalbridge.consultation.earnings;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SpecialistEarningsResponse(String currency, String earningPolicyVersion,
		int settlementHoldDays, long minimumWithdrawalVnd, Instant generatedAt, Balance balance,
		Destination destination, List<Earning> earnings, List<Payout> payouts) {

	public record Balance(long pendingSettlementVnd, long availableVnd, long processingVnd, long paidVnd) {
	}

	public record Destination(UUID id, String provider, String destinationType, String displayHint,
			String status, Instant verifiedAt) {
	}

	public record Earning(UUID id, UUID appointmentId, UUID consumedCreditId, String planVersion,
			long creditAllocationVnd, int sharePercent, long earningAmountVnd, String status,
			Instant earnedAt, Instant settlementAvailableAt) {
	}

	public record Payout(UUID id, UUID destinationId, long amountVnd, String provider, String status,
			String providerReference, String failureCode, Instant requestedAt, Instant completedAt) {
	}
}
