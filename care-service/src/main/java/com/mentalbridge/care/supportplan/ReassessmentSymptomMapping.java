package com.mentalbridge.care.supportplan;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog.Checklist;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SelectedResource;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SlotDraft;

public record ReassessmentSymptomMapping(UUID reassessmentSummaryId, UUID selfReportId, long selfReportRevision,
		Period currentPeriod, String catalogRevision, String mappingRevision, List<String> selectedCodes,
		List<ResourceReason> resourceReasons) {
	public ReassessmentSymptomMapping {
		Objects.requireNonNull(reassessmentSummaryId);
		Objects.requireNonNull(selfReportId);
		Objects.requireNonNull(currentPeriod);
		if (selfReportRevision < 0 || currentPeriod.startAt() == null || currentPeriod.endAt() == null
				|| !currentPeriod.startAt().isBefore(currentPeriod.endAt())
				|| !ReassessmentSymptomCatalog.released(new Checklist(catalogRevision, mappingRevision, selectedCodes))
				|| resourceReasons == null || resourceReasons.isEmpty() || resourceReasons.size() > 8) {
			throw new IllegalArgumentException("Invalid checklist mapping provenance");
		}
		selectedCodes = List.copyOf(selectedCodes);
		resourceReasons = List.copyOf(resourceReasons);
		var candidates = ReassessmentSymptomCatalog.candidates(new Checklist(catalogRevision, mappingRevision, selectedCodes));
		var reasonCodes = new HashSet<String>();
		for (var reason : resourceReasons) {
			if (reason == null || !reasonCodes.add(reason.code()) || candidates.stream().noneMatch(candidate ->
					candidate.code().equals(reason.code()) && candidate.resourceId().equals(reason.resourceId())
							&& candidate.slotIds().contains(reason.slotId()))) {
				throw new IllegalArgumentException("Invalid checklist resource reason");
			}
		}
	}

	boolean admits(SelectedResource selection) {
		return resourceReasons.stream().anyMatch(reason -> reason.slotId().equals(selection.slotId())
				&& reason.resourceId().equals(selection.resourceId())
				&& reason.contentVersion().equals(Long.toString(selection.contentVersion())));
	}

	boolean matches(List<SlotDraft> slots) {
		return resourceReasons.stream().allMatch(reason -> slots.stream().anyMatch(slot ->
				reason.slotId().equals(slot.slotId()) && reason.resourceId().equals(slot.selectedResource().resourceId())
						&& reason.contentVersion().equals(Long.toString(slot.selectedResource().contentVersion()))
						&& reason.publicationId().equals(slot.selectedResource().publicationId())
						&& reason.role().equals(slot.selectedResource().role())));
	}

	ReassessmentSymptomMapping retaining(List<SlotDraft> slots) {
		var retained = resourceReasons.stream().filter(reason -> slots.stream().anyMatch(slot ->
				reason.slotId().equals(slot.slotId()) && reason.resourceId().equals(slot.selectedResource().resourceId())
						&& reason.contentVersion().equals(Long.toString(slot.selectedResource().contentVersion()))
						&& reason.publicationId().equals(slot.selectedResource().publicationId())
						&& reason.role().equals(slot.selectedResource().role()))).toList();
		return retained.isEmpty() ? null : new ReassessmentSymptomMapping(reassessmentSummaryId, selfReportId,
				selfReportRevision, currentPeriod, catalogRevision, mappingRevision, selectedCodes, retained);
	}

	public record ResourceReason(String code, String slotId, UUID resourceId, String contentVersion,
			UUID publicationId, String role, String eligibilityPolicyVersion, String reasonCode) {
		public ResourceReason {
			Objects.requireNonNull(code);
			Objects.requireNonNull(resourceId);
			Objects.requireNonNull(publicationId);
			if (slotId == null || slotId.isBlank() || slotId.length() > 128 || contentVersion == null
					|| !contentVersion.matches("0|[1-9][0-9]{0,18}") || !List.of("PRIMARY", "ADJUNCT").contains(role)
					|| eligibilityPolicyVersion == null || eligibilityPolicyVersion.isBlank() || eligibilityPolicyVersion.length() > 128
					|| !"SELF_REPORTED_SYMPTOM_MATCH".equals(reasonCode)) {
				throw new IllegalArgumentException("Invalid exact checklist resource provenance");
			}
			Long.parseLong(contentVersion);
		}
	}
}
