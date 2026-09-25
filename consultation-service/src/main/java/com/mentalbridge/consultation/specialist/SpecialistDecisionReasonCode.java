package com.mentalbridge.consultation.specialist;

public enum SpecialistDecisionReasonCode {
	PROFILE_INFORMATION_INCOMPLETE(Kind.REJECTION),
	PROFILE_CONTENT_NOT_APPROVED(Kind.REJECTION),
	OUTSIDE_SUPPORTED_SCOPE(Kind.REJECTION),
	POLICY_VIOLATION(Kind.SUSPENSION),
	QUALITY_REVIEW_REQUIRED(Kind.SUSPENSION),
	ACCOUNT_REVIEW_REQUIRED(Kind.SUSPENSION);

	private final Kind kind;

	SpecialistDecisionReasonCode(Kind kind) {
		this.kind = kind;
	}

	boolean isRejection() {
		return kind == Kind.REJECTION;
	}

	boolean isSuspension() {
		return kind == Kind.SUSPENSION;
	}

	private enum Kind {
		REJECTION,
		SUSPENSION
	}
}
