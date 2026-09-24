package com.mentalbridge.care.reassessment;

import java.time.Instant;
import java.util.UUID;

import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;

public record ReassessmentSelfReportView(
		UUID selfReportId,
		String sourceVersion,
		Period currentPeriod,
		String currentExperience,
		String helpfulContext,
		String difficultContext,
		long version,
		Instant authoredAt,
		Instant updatedAt) { }
