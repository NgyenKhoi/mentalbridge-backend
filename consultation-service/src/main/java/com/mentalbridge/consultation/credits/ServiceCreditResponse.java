package com.mentalbridge.consultation.credits;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mentalbridge.consultation.entitlement.EntitlementSource;
import com.mentalbridge.consultation.entitlement.ServicePackage;

public record ServiceCreditResponse(UUID accountId, ServicePackage packageCode, EntitlementSource source,
		String sourceReference, Instant periodStart, Instant periodEnd, String policyVersion, Balance balance,
		ReservationCapacity reservationCapacity, List<LedgerEvent> history, Instant generatedAt) {

	public record Balance(int available, int held, int consumed, int forfeited, int total,
			int releasedTransitions) {
	}

	public record ReservationCapacity(int active, int maximum, int remaining) {
	}

	public record LedgerEvent(UUID eventId, UUID creditId, CreditEventType eventType, EntitlementSource source,
			ServicePackage packageCode, String policyVersion, UUID appointmentId, Instant occurredAt) {
	}
}
