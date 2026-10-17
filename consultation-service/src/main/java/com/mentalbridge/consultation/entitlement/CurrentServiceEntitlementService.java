package com.mentalbridge.consultation.entitlement;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentServiceEntitlementService {

	static final String POLICY_VERSION = "service-entitlement-v1";

	private final CurrentServiceEntitlementRepository entitlements;
	private final Clock clock;

	public CurrentServiceEntitlementService(CurrentServiceEntitlementRepository entitlements, Clock clock) {
		this.entitlements = entitlements;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public EntitlementDecision current(UUID accountId) {
		var now = clock.instant();
		return entitlements.findById(accountId)
			.filter(entitlement -> isEffective(entitlement, now))
			.map(entitlement -> EntitlementDecision.effective(entitlement, now))
			.orElseGet(() -> EntitlementDecision.defaultFree(accountId, now));
	}

	private boolean isEffective(CurrentServiceEntitlementEntity entitlement, Instant now) {
		return !entitlement.effectiveFrom().isAfter(now) && entitlement.effectiveUntil().isAfter(now);
	}

	public record EntitlementDecision(UUID accountId, ServicePackage packageCode, EntitlementSource source,
			String sourceReference, Instant effectiveFrom, Instant effectiveUntil, String policyVersion, long version,
			Instant decidedAt) {

		static EntitlementDecision effective(CurrentServiceEntitlementEntity entitlement, Instant decidedAt) {
			return new EntitlementDecision(entitlement.accountId(), entitlement.packageCode(), entitlement.source(),
					entitlement.sourceReference(), entitlement.effectiveFrom(), entitlement.effectiveUntil(),
					entitlement.policyVersion(), entitlement.version(), decidedAt);
		}

		static EntitlementDecision defaultFree(UUID accountId, Instant decidedAt) {
			return new EntitlementDecision(accountId, ServicePackage.FREE, EntitlementSource.DEFAULT_FREE, null, null,
					null, POLICY_VERSION, 0, decidedAt);
		}
	}
}
