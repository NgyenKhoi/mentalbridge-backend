package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.RequiredEligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityQuery;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityResult;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningInstrument;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.SupportTier;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationV2Service.DomainContributionView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.EvaluationView;

@Component
class SupportPlanPolicy {

	private static final String DEPRESSION = "DEPRESSIVE_SYMPTOMS";
	private static final String ANXIETY = "ANXIETY_SYMPTOMS";
	private static final String ARTICLE_DEPRESSION = "00000000-0000-4000-8000-000000000205";
	private static final String ACTIVITY_DEPRESSION = "00000000-0000-4000-8000-000000000208";
	private static final String ARTICLE_ANXIETY = "00000000-0000-4000-8000-000000000206";
	private static final String ACTIVITY_ANXIETY = "00000000-0000-4000-8000-000000000201";
	private static final String SLEEP_ADJUNCT = "00000000-0000-4000-8000-000000000207";
	private static final String JOURNAL_ADJUNCT = "00000000-0000-4000-8000-000000000212";

	ProposalRequest request(EvaluationView evaluation) {
		var families = new ArrayList<FamilyDraft>();
		var slots = new ArrayList<SlotSpec>();
		for (DomainContributionView domain : evaluation.contributingDomains()) {
			var family = family(domain);
			families.add(new FamilyDraft(family, domain.domain().name()));
			slots.addAll(slots(domain, family));
		}
		var queries = new ArrayList<ResourceEligibilityQuery>();
		for (SlotSpec slot : slots) {
			for (String resourceId : slot.resourceIds()) {
				queries.add(query(slot, resourceId));
			}
		}
		return new ProposalRequest(List.copyOf(families), List.copyOf(slots),
				new ResourceEligibilityBatchRequest(List.copyOf(queries)));
	}

	Proposal compose(ProposalRequest request, ResourceEligibilityBatchResponse response) {
		Map<String, ResourceEligibilityResult> byRequest = new HashMap<>();
		for (ResourceEligibilityResult result : response.results()) {
			if (result.outcome() == ResourceEligibilityOutcome.UNAVAILABLE) {
				throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RESOURCE_ELIGIBILITY_UNAVAILABLE",
						"Exact resource eligibility could not be verified");
			}
			if (result.outcome() == ResourceEligibilityOutcome.STALE
					|| result.outcome() == ResourceEligibilityOutcome.WITHDRAWN) {
				throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_VERSION_STALE",
						"An exact resource version is no longer eligible");
			}
			byRequest.put(result.requestId(), result);
		}

		Set<String> selectedVersions = new HashSet<>();
		var composed = new ArrayList<SlotDraft>();
		for (SlotSpec slot : request.slots()) {
			var eligible = slot.resourceIds().stream()
					.map(resourceId -> byRequest.get(requestId(slot.slotId(), resourceId)))
					.filter(result -> result != null && result.outcome() == ResourceEligibilityOutcome.ELIGIBLE)
					.map(this::resource)
					.toList();
			var selected = eligible.stream().filter(resource -> selectedVersions.add(resource.key())).findFirst().orElse(null);
			if (selected == null) {
				if ("CORE".equals(slot.kind())) {
					throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_CORE_UNAVAILABLE",
							"A required SupportPlan core slot has no eligible exact resource version");
				}
				continue;
			}
			var alternatives = eligible.stream().filter(resource -> !resource.key().equals(selected.key())).toList();
			composed.add(new SlotDraft(slot.slotId(), slot.kind(), slot.targetDomain(), slot.purposeCode(),
					selected, alternatives));
		}
		if (composed.isEmpty() || composed.size() > 5) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_BOUNDS_UNSATISFIED",
					"The deterministic proposal could not satisfy SupportPlan resource bounds");
		}
		return new Proposal(request.families(), List.copyOf(composed), response.policyVersion(),
				OffsetDateTime.parse(response.resolvedAt()).toInstant());
	}

	RevalidationRequest revalidationRequest(EvaluationView evaluation, List<SelectedResource> selections) {
		var proposal = request(evaluation);
		var slotsById = new HashMap<String, SlotSpec>();
		for (SlotSpec slot : proposal.slots()) {
			slotsById.put(slot.slotId(), slot);
		}
		var queries = new ArrayList<ResourceEligibilityQuery>();
		for (SelectedResource selection : selections) {
			var slot = slotsById.get(selection.slotId());
			if (slot == null || !slot.resourceIds().contains(selection.resourceId().toString())) {
				throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_POLICY_STALE",
						"The stored SupportPlan slot is no longer admitted by current template policy");
			}
			queries.add(query(slot, selection.resourceId().toString(), Long.toString(selection.contentVersion())));
		}
		return new RevalidationRequest(proposal.families(), proposal.slots(), selections,
				new ResourceEligibilityBatchRequest(List.copyOf(queries)));
	}

	RevalidationResult validate(RevalidationRequest request, ResourceEligibilityBatchResponse response) {
		Map<String, ResourceEligibilityResult> byRequest = new HashMap<>();
		for (ResourceEligibilityResult result : response.results()) {
			byRequest.put(result.requestId(), result);
		}
		for (SelectedResource selection : request.selections()) {
			var slot = request.slots().stream().filter(value -> value.slotId().equals(selection.slotId()))
					.findFirst().orElseThrow();
			var result = byRequest.get(requestId(slot.slotId(), selection.resourceId().toString()));
			if (result == null || result.outcome() == ResourceEligibilityOutcome.UNAVAILABLE) {
				throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RESOURCE_ELIGIBILITY_UNAVAILABLE",
						"Exact resource eligibility could not be verified");
			}
			if (result.outcome() != ResourceEligibilityOutcome.ELIGIBLE
					|| !Long.toString(selection.contentVersion()).equals(result.contentVersion())) {
				throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_VERSION_STALE",
						"A selected exact resource version is no longer eligible");
			}
			if ("CORE".equals(slot.kind()) && result.role() != com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.EligibilityRole.PRIMARY) {
				throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_INVALID_CHOICE",
						"A core SupportPlan slot requires PRIMARY eligibility");
			}
		}
		return new RevalidationResult(response.policyVersion(), OffsetDateTime.parse(response.resolvedAt()).toInstant());
	}

	private String family(DomainContributionView domain) {
		boolean minimal = domain.screeningLevel() == ScreeningLevel.MINIMAL;
		boolean mild = domain.screeningLevel() == ScreeningLevel.MILD;
		if (domain.domain().name().equals(DEPRESSION)) {
			return minimal ? "DEPRESSIVE_MAINTENANCE"
					: mild ? "DEPRESSIVE_SELF_GUIDED" : "DEPRESSIVE_PROFESSIONAL_ADJUNCT";
		}
		return minimal ? "ANXIETY_MAINTENANCE"
				: mild ? "ANXIETY_SELF_GUIDED" : "ANXIETY_PROFESSIONAL_ADJUNCT";
	}

	private List<SlotSpec> slots(DomainContributionView domain, String family) {
		String target = domain.domain().name();
		return switch (family) {
			case "DEPRESSIVE_MAINTENANCE" -> List.of(slot("depression-maintenance", "CORE", target,
					"DEPRESSION_MAINTENANCE", domain, RequiredEligibilityRole.PRIMARY,
					ACTIVITY_DEPRESSION, ARTICLE_DEPRESSION));
			case "DEPRESSIVE_SELF_GUIDED" -> List.of(
					slot("depression-psychoeducation", "CORE", target, "DEPRESSION_PSYCHOEDUCATION", domain,
							RequiredEligibilityRole.PRIMARY, ARTICLE_DEPRESSION),
					slot("depression-support-activity", "CORE", target, "DEPRESSION_SUPPORT_ACTIVITY", domain,
							RequiredEligibilityRole.PRIMARY, ACTIVITY_DEPRESSION));
			case "DEPRESSIVE_PROFESSIONAL_ADJUNCT" -> List.of(slot("depression-adjunct", "OPTIONAL", target,
					"DEPRESSION_SUPPORT_ADJUNCT", domain, RequiredEligibilityRole.PRIMARY_OR_ADJUNCT,
					SLEEP_ADJUNCT, JOURNAL_ADJUNCT, ACTIVITY_ANXIETY));
			case "ANXIETY_MAINTENANCE" -> List.of(slot("anxiety-maintenance", "CORE", target,
					"ANXIETY_MAINTENANCE", domain, RequiredEligibilityRole.PRIMARY,
					ACTIVITY_ANXIETY, ARTICLE_ANXIETY));
			case "ANXIETY_SELF_GUIDED" -> List.of(
					slot("anxiety-psychoeducation", "CORE", target, "ANXIETY_PSYCHOEDUCATION", domain,
							RequiredEligibilityRole.PRIMARY, ARTICLE_ANXIETY),
					slot("anxiety-support-activity", "CORE", target, "ANXIETY_SUPPORT_ACTIVITY", domain,
							RequiredEligibilityRole.PRIMARY, ACTIVITY_ANXIETY));
			default -> List.of(slot("anxiety-adjunct", "OPTIONAL", target, "ANXIETY_SUPPORT_ADJUNCT", domain,
					RequiredEligibilityRole.PRIMARY_OR_ADJUNCT,
					SLEEP_ADJUNCT, JOURNAL_ADJUNCT, ACTIVITY_DEPRESSION));
		};
	}

	private SlotSpec slot(String id, String kind, String domain, String purpose,
			DomainContributionView evidence, RequiredEligibilityRole role, String... resourceIds) {
		return new SlotSpec(id, kind, domain, purpose, role, evidence.instrument(), evidence.screeningLevel().name(),
				evidence.supportPathway().name(), List.of(resourceIds));
	}

	private ResourceEligibilityQuery query(SlotSpec slot, String resourceId) {
		return query(slot, resourceId, "0");
	}

	private ResourceEligibilityQuery query(SlotSpec slot, String resourceId, String contentVersion) {
		return new ResourceEligibilityQuery(requestId(slot.slotId(), resourceId), resourceId, contentVersion,
				com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningDomain.valueOf(slot.targetDomain()),
				slot.requiredRole(), "PHQ9".equals(slot.instrument()) ? ScreeningInstrument.PHQ_9 : ScreeningInstrument.GAD_7,
				com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningLevel.valueOf(slot.screeningLevel()),
				SupportTier.valueOf(slot.supportTier()), "vi-VN");
	}

	private String requestId(String slotId, String resourceId) {
		return UUID.nameUUIDFromBytes((slotId + ':' + resourceId).getBytes(StandardCharsets.UTF_8)).toString();
	}

	private ResourceDraft resource(ResourceEligibilityResult result) {
		return new ResourceDraft(UUID.fromString(result.resourceId()), Long.parseLong(result.contentVersion()),
				UUID.fromString(result.publicationId()), result.role().name(), result.category().name(), result.title(),
				result.summary(), result.externalUrl());
	}

	record ProposalRequest(List<FamilyDraft> families, List<SlotSpec> slots, ResourceEligibilityBatchRequest batch) { }
	record Proposal(List<FamilyDraft> families, List<SlotDraft> slots, String resourcePolicyVersion,
			java.time.Instant resourcesResolvedAt) { }
	record FamilyDraft(String family, String targetDomain) { }
	record SlotSpec(String slotId, String kind, String targetDomain, String purposeCode,
			RequiredEligibilityRole requiredRole, String instrument, String screeningLevel, String supportTier,
			List<String> resourceIds) { }
	record SlotDraft(String slotId, String kind, String targetDomain, String purposeCode,
			ResourceDraft selectedResource, List<ResourceDraft> allowedAlternatives) { }
	record ResourceDraft(UUID resourceId, long contentVersion, UUID publicationId, String role, String category,
			String title, String summary, String externalUrl) {
		String key() { return resourceId + ":" + contentVersion; }
	}
	record SelectedResource(String slotId, UUID resourceId, long contentVersion) { }
	record RevalidationRequest(List<FamilyDraft> families, List<SlotSpec> slots,
			List<SelectedResource> selections, ResourceEligibilityBatchRequest batch) { }
	record RevalidationResult(String resourcePolicyVersion, java.time.Instant resourcesResolvedAt) { }
}
