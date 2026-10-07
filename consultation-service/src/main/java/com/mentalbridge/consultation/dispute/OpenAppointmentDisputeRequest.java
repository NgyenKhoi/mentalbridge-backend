package com.mentalbridge.consultation.dispute;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

public record OpenAppointmentDisputeRequest(@NotNull ReasonCode reasonCode,
		EvidenceType evidenceType, Instant evidenceOccurredAt) {

	public enum ReasonCode {
		OUTCOME_INCORRECT,
		PARTICIPATION_EVIDENCE_INCORRECT,
		SESSION_DELIVERY_NOT_RECOGNIZED,
		TECHNICAL_FAILURE
	}

	public enum EvidenceType {
		ACCESS_LOG,
		CONNECTION_INCIDENT,
		PROVIDER_INCIDENT
	}
}
