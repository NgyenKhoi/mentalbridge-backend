package com.mentalbridge.consultation.appointment;

import java.time.Instant;
import java.util.UUID;

public record AppointmentChatEligibility(UUID conversationId, UUID appointmentId, UUID userAccountId,
		UUID specialistAccountId, Phase phase, String reasonCode, boolean subscribeAllowed, boolean sendAllowed,
		boolean historyAllowed, boolean checkInAllowed, boolean participantCheckedIn, String sessionOutcome,
		String creditState, Instant scheduledStartAt, Instant scheduledEndAt, Instant serverTime) {

	public enum Phase {
		NOT_AVAILABLE, TOO_EARLY, WAITING, ACTIVE, ENDED_PROCESSING, COMPLETED, USER_NO_SHOW,
		SPECIALIST_NO_SHOW, BOTH_NO_SHOW, INSUFFICIENT_EVIDENCE, EVIDENCE_REVIEW, CANCELLED, RESCHEDULED
	}

	public enum Operation {
		SUBSCRIBE, SEND, HISTORY, CHECK_IN
	}
}
