package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.UUID;

public record VideoSessionResponse(UUID appointmentId, String phase, boolean previewAllowed,
		boolean joinAllowed, Instant startsAt, Instant endsAt, Instant serverTime, long version,
		VideoCompletionResponse completion) { }
