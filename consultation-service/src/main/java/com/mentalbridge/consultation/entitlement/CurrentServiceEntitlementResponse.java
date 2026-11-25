package com.mentalbridge.consultation.entitlement;

import java.time.Instant;
import java.util.UUID;

public record CurrentServiceEntitlementResponse(UUID accountId, ServicePackage packageCode, EntitlementSource source,
		String sourceReference, Instant effectiveFrom, Instant effectiveUntil, String policyVersion, long version,
		Instant decidedAt) {

	static CurrentServiceEntitlementResponse from(CurrentServiceEntitlementService.EntitlementDecision decision) {
		return new CurrentServiceEntitlementResponse(decision.accountId(), decision.packageCode(), decision.source(),
				decision.sourceReference(), decision.effectiveFrom(), decision.effectiveUntil(), decision.policyVersion(),
				decision.version(), decision.decidedAt());
	}
}
