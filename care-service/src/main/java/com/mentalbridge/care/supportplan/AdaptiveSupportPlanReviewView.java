package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.mentalbridge.care.reassessment.AdaptiveReviewEvidenceService.OccurrenceEvidence;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.SelfReportedExperienceDimension;

public record AdaptiveSupportPlanReviewView(String schemaVersion, String policyVersion, UUID supportPlanId,
		long planVersion, String planStatus, SupportPlanCycle.View cycle, Instant reviewedAt,
		ProgramPeriod programPeriod, String reviewState, Reassessment reassessment, String currentAdmission,
		String outcome, List<Pattern> patterns, List<Contradiction> contradictions, List<Option> options,
		List<UnavailableOption> unavailableOptions, String reviewFingerprint, EvidenceContext evidenceContext) {
	public record ProgramPeriod(LocalDate startedOn, LocalDate asOf, String timezone, int elapsedDays,
			int pausedDays, boolean fullCycleElapsed, int scheduledCount, int completedCount, int skippedCount,
			int openCount) { }
	public record Reassessment(String state, String reasonCode, ReassessmentSummaryView summary) { }
	public record EvidenceRef(String source, String refId) { }
	public record Pattern(String patternId, String kind, String code, UUID actionId, String actionTitle,
			int count, List<UUID> occurrenceIds) { }
	public record Contradiction(String contradictionId, String kind, List<EvidenceRef> evidenceRefs) { }
	public record Change(String field, String before, String after) { }
	public record Option(String optionId, String kind, String executionKind, UUID actionId, String focusId,
			List<String> rationaleCodes, List<EvidenceRef> evidenceRefs, List<EvidenceRef> conflictingEvidenceRefs,
			List<Change> changes) { }
	public record UnavailableOption(String kind, String reasonCode) { }
	public record EvidenceContext(String comparisonPolicyVersion, Period previousPeriod, Period currentPeriod,
			String timezone, SelfReportedExperienceDimension selfReportedExperience,
			List<OccurrenceEvidence> engagementSources) { }
	public record Confirmation(String result, String optionId, SupportPlanV3Service.SupportPlanV3View plan,
			SupportPlanV3Service.SupportPlanV3View draft) { }
}
