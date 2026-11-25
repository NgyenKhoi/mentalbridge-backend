package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.UUID;

public record VideoCompletionResponse(UUID appointmentId, UUID confirmationId, String verificationState,
		String appointmentStatus, String creditState, UUID completionFactId, Instant acceptedAt,
		Instant manualReviewAt, long version) {

	static VideoCompletionResponse from(VideoAppointment appointment, VideoConfirmation confirmation, String creditState) {
		var state = appointment.completionFactId != null ? "VERIFIED"
				: appointment.reviewStartedAt != null ? "EVIDENCE_REVIEW" : "PENDING_VERIFICATION";
		return new VideoCompletionResponse(appointment.id, confirmation.id, state, appointment.status, creditState,
				appointment.completionFactId, confirmation.acceptedAt, confirmation.acceptedAt.plusSeconds(86400), appointment.version);
	}
}
