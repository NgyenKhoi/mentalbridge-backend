package com.mentalbridge.consultation.dashboard;

import java.time.Instant;

public record AdminOperationsResponse(
		String source,
		Instant asOf,
		AdminSpecialistOperationsSummary specialists,
		AdminAppointmentOperationsSummary appointments) {

	public record AdminSpecialistOperationsSummary(
			long total,
			long pendingReview,
			long active,
			long rejected,
			long suspended) {
	}

	public record AdminAppointmentOperationsSummary(
			long total,
			long requested,
			long confirmed,
			long inProgress,
			long sessionEnded,
			long completed,
			long cancelled,
			long rejected,
			long expired,
			long userNoShow,
			long specialistNoShow,
			long disputed) {
	}
}

