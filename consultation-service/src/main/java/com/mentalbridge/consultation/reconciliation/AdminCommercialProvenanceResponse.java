package com.mentalbridge.consultation.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mentalbridge.consultation.billing.AdminPaymentResponse;
import com.mentalbridge.consultation.earnings.AdminPayoutResponse;

public record AdminCommercialProvenanceResponse(
		Instant generatedAt,
		String queryIdentifierType,
		String queryIdentifierValue,
		boolean found,
		boolean truncated,
		AdminSubscriptionSummary subscription,
		List<AdminSubscriptionSummary> subscriptions,
		List<AdminPaymentResponse.Item> payments,
		List<AdminCreditSummary> credits,
		List<AdminAppointmentSummary> appointments,
		List<AdminDisputeSummary> disputes,
		List<AdminEarningSummary> earnings,
		List<AdminPayoutResponse.Item> payouts) {

	public AdminCommercialProvenanceResponse(
			Instant generatedAt,
			String queryIdentifierType,
			String queryIdentifierValue,
			boolean found,
			AdminSubscriptionSummary subscription,
			List<AdminPaymentResponse.Item> payments,
			List<AdminCreditSummary> credits,
			List<AdminAppointmentSummary> appointments,
			List<AdminDisputeSummary> disputes,
			List<AdminEarningSummary> earnings,
			List<AdminPayoutResponse.Item> payouts) {
		this(generatedAt, queryIdentifierType, queryIdentifierValue, found, false, subscription,
				subscription == null ? List.of() : List.of(subscription),
				payments, credits, appointments, disputes, earnings, payouts);
	}

	public record AdminSubscriptionSummary(
			UUID subscriptionId,
			UUID userAccountId,
			UUID planVersionId,
			String planCode,
			String pricingPolicyVersion,
			String status,
			Instant currentPeriodStart,
			Instant currentPeriodEnd,
			Instant createdAt) {
	}

	public record AdminCreditSummary(
			UUID creditId,
			UUID periodId,
			int ordinal,
			String state,
			long creditAllocationVnd,
			UUID appointmentId,
			Instant createdAt) {
	}

	public record AdminAppointmentSummary(
			UUID appointmentId,
			UUID specialistAccountId,
			UUID serviceCreditId,
			String status,
			String modality,
			String sessionOutcome,
			UUID completionFactId,
			Instant scheduledStartAt,
			Instant scheduledEndAt,
			Instant sessionSettledAt) {
	}

	public record AdminDisputeSummary(
			UUID disputeId,
			UUID appointmentId,
			String openedByRole,
			String reasonCode,
			String status,
			String resolutionOutcome,
			String resolutionReason,
			Instant openedAt,
			Instant resolvedAt) {
	}

	public record AdminEarningSummary(
			UUID earningId,
			UUID appointmentId,
			UUID consumedCreditId,
			UUID specialistAccountId,
			String planVersion,
			long creditAllocationVnd,
			int specialistShareBps,
			long specialistAmountVnd,
			long platformAllocationVnd,
			String status,
			Instant earnedAt,
			Instant settlementAvailableAt,
			Instant paidAt) {
	}
}

