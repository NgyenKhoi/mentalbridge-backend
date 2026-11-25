package com.mentalbridge.consultation.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanCatalogueResponse(List<Plan> plans, boolean checkoutEnabled, String paymentProvider,
		Instant generatedAt) {

	public record Plan(UUID planVersionId, String planCode, String version, String displayName, String currency,
			String pricingPolicyVersion,
			long priceVnd, int billingPeriodMonths, int consultationCredits, int maxActiveReservations,
			long creditAllocationVnd, String aiQuotaCode, boolean supportPlanEnabled, List<String> entitlements,
			List<String> allowedUpgrades, Instant effectiveFrom) { }
}
