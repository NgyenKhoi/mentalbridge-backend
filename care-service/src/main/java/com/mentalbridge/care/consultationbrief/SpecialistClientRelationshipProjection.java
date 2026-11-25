package com.mentalbridge.care.consultationbrief;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SpecialistClientRelationshipProjection(List<Item> items, int count, Instant generatedAt,
		Instant recentSince, String policyVersion) {
	public record Item(UUID appointmentId, UUID userAccountId, String status, String modality,
			Instant scheduledStartAt, Instant scheduledEndAt, long appointmentVersion) { }
}
