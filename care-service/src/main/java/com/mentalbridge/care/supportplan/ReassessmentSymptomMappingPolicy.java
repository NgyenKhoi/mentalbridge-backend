package com.mentalbridge.care.supportplan;

import static com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewPolicy.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ExplicitSelfReport;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog.Checklist;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewPolicy.Candidate;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.Change;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.EvidenceRef;
import com.mentalbridge.care.supportplan.ReassessmentSymptomMapping.ResourceReason;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.Proposal;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SlotDraft;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SlotSpec;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.Template;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.SupportPlanV3View;

final class ReassessmentSymptomMappingPolicy {
	private static final String MATCH = "SELF_REPORTED_SYMPTOM_MATCH";
	private ReassessmentSymptomMappingPolicy() { }

	static Template expand(Template template, Checklist checklist) {
		var mapped = ReassessmentSymptomCatalog.candidates(checklist);
		return new Template(template.families(), template.slots().stream().map(slot -> {
			var ids = new ArrayList<>(slot.resourceIds());
			mapped.stream().filter(item -> item.slotIds().contains(slot.slotId())).map(item -> item.resourceId().toString())
					.filter(id -> !ids.contains(id)).forEach(ids::add);
			return new SlotSpec(slot.slotId(), slot.kind(), slot.targetDomain(), slot.purposeCode(), slot.requiredRole(),
					slot.instrument(), slot.screeningLevel(), slot.supportTier(), List.copyOf(ids));
		}).toList());
	}

	static List<Candidate> options(AdaptiveSupportPlanReviewPolicy policy, SupportPlanV3View plan, Proposal base, Template standard,
			UUID summaryId, ExplicitSelfReport source, UUID evaluationId, List<EvidenceRef> conflicts, List<Candidate> existing) {
		if (!"NATIVE_V3".equals(plan.representationMode()) || source == null || source.symptomChecklist() == null
				|| !ReassessmentSymptomCatalog.released(source.symptomChecklist())) return List.of();
		var catalogCandidates = ReassessmentSymptomCatalog.candidates(source.symptomChecklist());
		var result = new ArrayList<Candidate>();
		for (var slot : base.slots()) {
			var action = actions(plan).stream().filter(item -> "RESOURCE".equals(item.actionType())
					&& slot.slotId().equals(item.provenance().referenceId())).findFirst().orElse(null);
			if (action == null) continue;
			var available = new ArrayList<>(slot.allowedAlternatives());
			available.addFirst(slot.selectedResource());
			for (var candidate : catalogCandidates) {
				if (!candidate.slotIds().contains(slot.slotId())) continue;
				var resource = available.stream().filter(item -> item.resourceId().equals(candidate.resourceId())).findFirst().orElse(null);
				if (resource == null || resource.resourceId().toString().equals(action.source().sourceId())
						&& Long.toString(resource.contentVersion()).equals(action.source().sourceRevision())
						|| base.slots().stream().anyMatch(other -> !other.slotId().equals(slot.slotId())
								&& other.selectedResource().key().equals(resource.key()))) continue;
				var slots = base.slots().stream().map(item -> slot.slotId().equals(item.slotId())
						? new SlotDraft(item.slotId(), item.kind(), item.targetDomain(), item.purposeCode(), resource, List.of()) : item).toList();
				var reasons = catalogCandidates.stream().flatMap(item -> slots.stream().filter(selected ->
						item.slotIds().contains(selected.slotId()) && item.resourceId().equals(selected.selectedResource().resourceId()))
						.map(selected -> new ResourceReason(item.code(), selected.slotId(), item.resourceId(),
								Long.toString(selected.selectedResource().contentVersion()), selected.selectedResource().publicationId(),
								selected.selectedResource().role(), base.resourcePolicyVersion(), "SELF_REPORTED_SYMPTOM_MATCH"))).toList();
				var mapping = new ReassessmentSymptomMapping(summaryId, source.selfReportId(), source.sourceRevision(), source.currentPeriod(),
						source.symptomChecklist().catalogRevision(), source.symptomChecklist().mappingRevision(), source.symptomChecklist().selectedCodes(), reasons);
				if (base.symptomMapping() != null && base.symptomMapping().resourceReasons().stream().anyMatch(old ->
						slots.stream().anyMatch(selected -> old.slotId().equals(selected.slotId())
								&& old.resourceId().equals(selected.selectedResource().resourceId())
								&& old.contentVersion().equals(Long.toString(selected.selectedResource().contentVersion())))
						&& standard.slots().stream().noneMatch(spec -> spec.slotId().equals(old.slotId())
								&& spec.resourceIds().contains(old.resourceId().toString()))
						&& !mapping.admits(new SupportPlanPolicy.SelectedResource(old.slotId(), old.resourceId(), Long.parseLong(old.contentVersion()))))) continue;
				var desired = new Proposal(base.families(), slots, base.resourcePolicyVersion(), base.resourcesResolvedAt(), mapping);
				var evidence = new EvidenceRef("SELF_REPORTED_EXPERIENCE", source.selfReportId().toString());
				var same = existing.stream().filter(item -> item.proposal() != null && sameIntent(item.proposal(), desired)).findFirst().orElse(null);
				if (same != null) {
					merge(policy, existing, same, mapping, evidence);
					break;
				}
				var option = policy.option("ALTERNATIVE_ACTION", "PREPARE_REPLACEMENT", UUID.fromString(action.actionId()), null,
						List.of(MATCH), List.of(evidence),
						conflicts, List.of(new Change("ACTION", action.presentation().title(), resource.title())), mapping);
				result.add(new Candidate(option, null, desired, evaluationId));
				break;
			}
		}
		return List.copyOf(result);
	}

	private static void merge(AdaptiveSupportPlanReviewPolicy policy, List<Candidate> existing, Candidate same,
			ReassessmentSymptomMapping mapping, EvidenceRef evidence) {
		var proposal = same.proposal();
		if (!mapping.matches(proposal.slots()) || mapping.resourceReasons().stream()
				.anyMatch(reason -> !reason.eligibilityPolicyVersion().equals(proposal.resourcePolicyVersion()))) return;
		var option = same.option();
		var reasons = new ArrayList<>(option.rationaleCodes());
		if (!reasons.contains(MATCH)) reasons.add(MATCH);
		var refs = new ArrayList<>(option.evidenceRefs());
		if (!refs.contains(evidence)) refs.add(evidence);
		var merged = policy.option(option.kind(), option.executionKind(), option.actionId(), option.focusId(), List.copyOf(reasons),
				List.copyOf(refs), option.conflictingEvidenceRefs(), option.changes(), mapping);
		existing.set(existing.indexOf(same), new Candidate(merged, same.schedule(), new Proposal(proposal.families(), proposal.slots(),
				proposal.resourcePolicyVersion(), proposal.resourcesResolvedAt(), mapping), same.evaluationId()));
	}

	private static boolean sameIntent(Proposal first, Proposal second) {
		return first.families().equals(second.families()) && first.slots().stream().map(slot -> slot.slotId() + ':' + slot.selectedResource().key()).sorted().toList()
				.equals(second.slots().stream().map(slot -> slot.slotId() + ':' + slot.selectedResource().key()).sorted().toList());
	}
}
