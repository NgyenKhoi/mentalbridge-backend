package com.mentalbridge.community.moderation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class CommunityModerationModels {

	private CommunityModerationModels() {
	}

	enum TargetType { POST, COMMENT }

	enum ReportReason {
		HARASSMENT, PRIVACY_OR_DOXXING, MEDICAL_MISINFORMATION, SELF_HARM_OR_CRISIS_CONCERN,
		SPAM, SEXUAL_OR_VIOLENT_CONTENT, OTHER
	}

	enum CaseState { OPEN, IN_REVIEW, RESOLVED }

	enum Priority { NORMAL, HIGH }

	enum Action {
		NO_ACTION, HIDE, REMOVE, RESTORE, RESTRICT_COMMUNITY_ACCESS,
		APPLY_SENSITIVE_WARNING, REMOVE_SENSITIVE_WARNING
	}

	record CreateReportRequest(TargetType targetType, UUID targetId, ReportReason reason, String details) {
	}

	record CreateModerationActionRequest(Action action, String reasonCode) {
	}

	record Evidence(String content, String state, long version) {
	}

	record ActionRecord(UUID actionId, Action action, String reasonCode, UUID actorSubject,
			String priorState, String resultingState, long targetVersion, Instant createdAt) {
	}

	record ModerationCase(UUID caseId, TargetType targetType, UUID targetId, CaseState state,
			Priority priority, List<ReportReason> reportReasons, List<String> reportContexts, Evidence evidence,
			List<ActionRecord> actions, Instant createdAt, Instant updatedAt, long version) {
	}

	record CommunityOperationsSummary(String source, Instant asOf, long openModerationCases, long totalModerationCases) {
	}
}
