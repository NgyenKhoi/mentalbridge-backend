package com.mentalbridge.consultation.summary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SessionSummaryResponse(UUID id, UUID appointmentId, UUID userAccountId,
		UUID specialistAccountId, long version, String schemaVersion, List<String> topicsDiscussed,
		String progressSummary, String specialistNoteForUser, boolean followUpSuggested,
		UUID amendsSummaryId, Instant publishedAt, ReuseConsent reuseConsent,
		List<AgreedNextStep> agreedNextSteps) {

	public record ReuseConsent(boolean approved, long version, Instant updatedAt) {
	}

	public record AgreedNextStep(UUID id, AgreedNextStepType type, String title, String details,
			UUID resourceId, String resourceVersion, AgreedNextStepState state, boolean hidden,
			Long stateVersion, Instant stateUpdatedAt) {
	}

	public record ListResponse(List<SessionSummaryResponse> items, int count, Instant generatedAt) {
	}
}
