package com.mentalbridge.consultation.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminCommercialDiscrepancyResponse(
		Instant generatedAt,
		int totalDiscrepancies,
		List<WebhookDiscrepancy> webhooks,
		List<PayoutDiscrepancy> payouts,
		List<HeldEarningDiscrepancy> heldEarnings,
		List<DisputeDiscrepancy> disputes) {

	public record WebhookDiscrepancy(
			UUID id,
			String orderId,
			String provider,
			Long transId,
			String discrepancyType,
			String status,
			String failureCode,
			Instant receivedAt) {
	}

	public record PayoutDiscrepancy(
			UUID payoutId,
			UUID specialistAccountId,
			String destinationHint,
			long amountVnd,
			String currency,
			String provider,
			String status,
			String failureCode,
			Instant requestedAt) {
	}

	public record HeldEarningDiscrepancy(
			UUID earningId,
			UUID appointmentId,
			UUID specialistAccountId,
			long amountVnd,
			String status,
			String holdReason,
			Instant earnedAt,
			Instant settlementAvailableAt) {
	}

	public record DisputeDiscrepancy(
			UUID disputeId,
			UUID appointmentId,
			String openedByRole,
			String reasonCode,
			String status,
			String resolutionOutcome,
			Instant openedAt,
			Instant eligibleUntil) {
	}
}
