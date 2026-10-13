package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.List;

public interface VideoProvider {

	String issueToken(String room, String identity, Instant issuedAt, Instant expiresAt);
	void prepareRoom(String room);
	void closeRoom(String room, List<String> identities, Instant cutoff);
	VerifiedVideoEvent verifyWebhook(String body, String authorization);

	record VerifiedVideoEvent(String id, String type, String room, String roomSid,
			String identity, String participantSid, Instant occurredAt) { }
}
