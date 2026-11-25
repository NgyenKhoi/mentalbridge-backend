package com.mentalbridge.care.entitlement;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CurrentEntitlementResponse(UUID accountId, ServicePackage packageCode, EntitlementSource source,
		String sourceReference, Instant effectiveFrom, Instant effectiveUntil, String policyVersion, long version,
		Instant decidedAt) {

	public enum ServicePackage { FREE, PLUS, PREMIUM }
	public enum EntitlementSource { DEFAULT_FREE, DEMO, PAID }
}
