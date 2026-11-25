package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.UUID;

public record VideoAccessResponse(UUID appointmentId, String provider, String serverUrl, String roomName,
		String participantToken, Instant expiresAt, Instant endsAt) {

	@Override
	public String toString() { return "VideoAccessResponse[credentials redacted]"; }
}
