package com.mentalbridge.consultation.appointment;

import java.time.Instant;
import java.util.UUID;

public record AppointmentChatEligibility(UUID conversationId, UUID appointmentId, UUID userAccountId,
		UUID specialistAccountId, Phase phase, String reasonCode, boolean subscribeAllowed, boolean sendAllowed,
		boolean historyAllowed, Instant scheduledStartAt, Instant scheduledEndAt, Instant serverTime) {

	public enum Phase {
		NOT_AVAILABLE, TOO_EARLY, WAITING, ACTIVE, ENDED, CANCELLED, RESCHEDULED
	}

	public enum Operation {
		SUBSCRIBE, SEND, HISTORY
	}
}
