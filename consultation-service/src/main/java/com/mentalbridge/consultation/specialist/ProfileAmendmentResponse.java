package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.UUID;

public record ProfileAmendmentResponse(UUID id, UUID specialistAccountId, long basePublishedVersion,
		ProfileAmendmentEntity.Status status, SpecialistProfileService.ProfileCommand proposedProfile,
		Instant submittedAt, Instant reviewedAt, UUID reviewedBy, SpecialistDecisionReasonCode reasonCode,
		Instant createdAt, Instant updatedAt, long version) {

	static ProfileAmendmentResponse from(ProfileAmendmentEntity value) {
		return new ProfileAmendmentResponse(value.id(), value.specialistAccountId(), value.basePublishedVersion(),
				value.status(), value.proposedProfile(), value.submittedAt(), value.reviewedAt(), value.reviewedBy(),
				value.reasonCode(), value.createdAt(), value.updatedAt(), value.version());
	}
}
