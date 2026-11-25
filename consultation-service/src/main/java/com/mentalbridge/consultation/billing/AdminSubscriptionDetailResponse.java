package com.mentalbridge.consultation.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminSubscriptionDetailResponse(
		UUID subscriptionId,
		UUID userAccountId,
		UUID planVersionId,
		String planCode,
		String pricingPolicyVersion,
		String status,
		Instant currentPeriodStart,
		Instant currentPeriodEnd,
		Instant createdAt,
		List<AdminPaymentResponse.Item> payments,
		List<CreditItem> credits,
		List<UpgradeItem> upgrades) {

	public record CreditItem(
			UUID creditId,
			UUID periodId,
			int ordinal,
			String state,
			long creditAllocationVnd,
			UUID appointmentId,
			Instant createdAt) {
	}

	public record UpgradeItem(
			UUID upgradeId,
			UUID fromPlanVersionId,
			UUID toPlanVersionId,
			long offsetVnd,
			long amountDueVnd,
			String status,
			Instant quotedAt,
			Instant appliedAt) {
	}
}
