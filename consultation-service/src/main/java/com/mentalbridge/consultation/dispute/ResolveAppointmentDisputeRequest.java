package com.mentalbridge.consultation.dispute;

import jakarta.validation.constraints.NotNull;

public record ResolveAppointmentDisputeRequest(@NotNull Outcome outcome, @NotNull ReasonCode reasonCode) {

	public enum Outcome {
		UPHOLD_RECORDED_OUTCOME,
		RELEASE_USER_CREDIT
	}

	public enum ReasonCode {
		EVIDENCE_SUPPORTS_RECORDED_OUTCOME,
		EVIDENCE_INCONCLUSIVE_RELEASED,
		TECHNICAL_FAILURE_CONFIRMED
	}
}
