package com.mentalbridge.care.reassessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog.Checklist;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog.SelectionReason;
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
		Instant updatedAt,
		@JsonInclude(JsonInclude.Include.NON_NULL) Checklist symptomChecklist,
		@JsonInclude(JsonInclude.Include.NON_NULL) List<SelectionReason> symptomSelectionReasons) { }
