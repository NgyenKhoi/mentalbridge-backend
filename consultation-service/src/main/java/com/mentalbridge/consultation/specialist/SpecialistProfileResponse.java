package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SpecialistProfileResponse(UUID accountId, String displayName, String bio,
		List<SupportArea> supportAreas, List<String> languages, int yearsOfExperience, String timezone,
		SpecialistApprovalStatus approvalStatus, Instant submittedAt, Instant reviewedAt, UUID reviewedBy,
		String decisionReasonCode, Instant createdAt, Instant updatedAt, long version) {

	static SpecialistProfileResponse from(SpecialistProfileService.ProfileView value) {
		return new SpecialistProfileResponse(value.accountId(), value.displayName(), value.bio(), value.supportAreas(),
				value.languages(), value.yearsOfExperience(), value.timezone(), value.approvalStatus(),
				value.submittedAt(), value.reviewedAt(), value.reviewedBy(), value.decisionReasonCode(),
				value.createdAt(), value.updatedAt(), value.version());
	}
}
