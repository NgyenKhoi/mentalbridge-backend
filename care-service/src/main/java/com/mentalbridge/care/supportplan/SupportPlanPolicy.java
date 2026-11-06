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
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.ActivityMode;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationSnapshot;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PreferredFocus;

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
	Template template(EvaluationView evaluation) {
		return template(evaluation, null);
	}

	Template template(EvaluationView evaluation, PersonalizationSnapshot personalization) {
		var families = new ArrayList<FamilyDraft>();
		var slots = new ArrayList<SlotSpec>();
		String personalizedDomain = personalizedDomain(evaluation, personalization);
		for (DomainContributionView domain : evaluation.contributingDomains()) {
			var family = family(domain);
			var focus = domain.domain().name().equals(personalizedDomain)
					? personalizedFocus(domain.domain().name(), personalization) : null;
			families.add(new FamilyDraft(family, domain.domain().name(),
					focus == null ? null : focus.title(), focus == null ? null : focus.rationale(),
					focus == null ? null : "USER_PREFERENCE_AND_CARE_POLICY"));
			slots.addAll(slots(domain, family));
		}
		return new Template(List.copyOf(families), List.copyOf(slots));
	}

	ProposalRequest request(Template template, Map<String, String> currentVersions) {
		var queries = new ArrayList<ResourceEligibilityQuery>();
		for (SlotSpec slot : template.slots()) {
			for (String resourceId : slot.resourceIds()) {
				if (!currentVersions.containsKey(resourceId)) {
					throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RESOURCE_ELIGIBILITY_UNAVAILABLE",
							"Current resource versions could not be verified");
				}
				String version = currentVersions.get(resourceId);
				if (version != null) queries.add(query(slot, resourceId, version));
			}
		}
		return new ProposalRequest(template.families(), template.slots(),
				new ResourceEligibilityBatchRequest(List.copyOf(queries)));
	}

	Proposal compose(ProposalRequest request, ResourceEligibilityBatchResponse response) {
		return compose(request, response, null);
	}

	Proposal compose(ProposalRequest request, ResourceEligibilityBatchResponse response,
			PersonalizationSnapshot personalization) {
		return compose(request, response, personalization, false);
	}

	Proposal composeNative(ProposalRequest request, ResourceEligibilityBatchResponse response,
			PersonalizationSnapshot personalization) {
		return compose(request, response, personalization, true);
	}

	private Proposal compose(ProposalRequest request, ResourceEligibilityBatchResponse response,
			PersonalizationSnapshot personalization, boolean allowNoResources) {
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
					.sorted(java.util.Comparator.comparingInt(resource -> preferenceRank(resource, personalization)))
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
		if ((composed.isEmpty() && !allowNoResources) || composed.size() > 5) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_BOUNDS_UNSATISFIED",
					"The deterministic proposal could not satisfy SupportPlan resource bounds");
		}
		return new Proposal(request.families(), List.copyOf(composed), response.policyVersion(),
				OffsetDateTime.parse(response.resolvedAt()).toInstant());
	}

	private int preferenceRank(ResourceDraft resource, PersonalizationSnapshot personalization) {
		if (personalization == null) return Integer.MAX_VALUE;
		for (int index = 0; index < personalization.preferredActivityModes().size(); index++) {
			if (matches(personalization.preferredActivityModes().get(index), resource.category())) return index;
		}
		return Integer.MAX_VALUE;
	}

	private boolean matches(ActivityMode mode, String category) {
		return switch (mode) {
			case READING -> List.of("ARTICLE", "VIDEO").contains(category);
			case GUIDED_PRACTICE -> List.of("BREATHING", "MEDITATION").contains(category);
			case JOURNALING -> "JOURNALING".equals(category);
			case EMOTION_CHECK_IN, ROUTINE -> false;
		};
	}

	private String personalizedDomain(EvaluationView evaluation, PersonalizationSnapshot personalization) {
		if (!hasPreferences(personalization)) return null;
		var domains = evaluation.contributingDomains().stream().map(value -> value.domain().name()).toList();
		PreferredFocus preferredFocus = personalization.preferredFocus();
		if (preferredFocus == null) return domains.getFirst();
		String preferred = switch (preferredFocus) {
			case ANXIETY -> ANXIETY;
			case MOOD, MOTIVATION -> DEPRESSION;
			case SLEEP, UNDERSTANDING_EMOTIONS -> null;
		};
		return preferred != null && domains.contains(preferred) ? preferred : null;
	}

	private boolean hasPreferences(PersonalizationSnapshot value) {
		return value != null && (value.preferredFocus() != null || value.desiredPace() != null
				|| value.dailyTimeBudgetMinutes() != null || value.preferredDaypart() != null
				|| !value.preferredActivityModes().isEmpty() || !value.commonBarriers().isEmpty());
	}

	private FocusCopy personalizedFocus(String domain, PersonalizationSnapshot personalization) {
		PreferredFocus preferredFocus = personalization.preferredFocus();
		String title = preferredFocus == null
				? domainFocusTitle(domain)
				: switch (preferredFocus) {
			case ANXIETY -> "Làm dịu lo âu";
			case MOOD -> "Chăm sóc tâm trạng";
			case MOTIVATION -> "Bắt đầu bằng bước nhỏ";
			case SLEEP, UNDERSTANDING_EMOTIONS -> domainFocusTitle(domain);
		};
		String rationale = preferredFocus == null
				|| preferredFocus == PreferredFocus.SLEEP
				|| preferredFocus == PreferredFocus.UNDERSTANDING_EMOTIONS
				? "Bạn đã chia sẻ điều gì phù hợp với nhịp sống của mình. Care chỉ dùng các lựa chọn này trong những hoạt động đã được chính sách cho phép."
				: "Bạn đã chọn trọng tâm này. Care chỉ dùng lựa chọn đó để sắp xếp những hoạt động hỗ trợ sức khỏe tổng quát đã được chính sách cho phép.";
		return new FocusCopy(title, rationale);
	}

	private String domainFocusTitle(String domain) {
		return ANXIETY.equals(domain) ? "Hỗ trợ bình tâm" : "Hỗ trợ tâm trạng";
	}

	RevalidationRequest revalidationRequest(EvaluationView evaluation, List<SelectedResource> selections) {
		return revalidationRequest(evaluation, selections, null);
	}

	RevalidationRequest revalidationRequest(EvaluationView evaluation, List<SelectedResource> selections,
			ReassessmentSymptomMapping mapping) {
		var proposal = template(evaluation, null, mapping);
		if (mapping != null && mapping.resourceReasons().stream().anyMatch(reason -> selections.stream().noneMatch(selection ->
				reason.slotId().equals(selection.slotId()) && reason.resourceId().equals(selection.resourceId())
						&& reason.contentVersion().equals(Long.toString(selection.contentVersion()))))) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_POLICY_STALE", "Stored checklist provenance no longer matches selected resources");
		}
		var slotsById = new HashMap<String, SlotSpec>();
		for (SlotSpec slot : proposal.slots()) {
			slotsById.put(slot.slotId(), slot);
		}
		var queries = new ArrayList<ResourceEligibilityQuery>();
		for (SelectedResource selection : selections) {
			var slot = slotsById.get(selection.slotId());
			if (slot == null || !slot.resourceIds().contains(selection.resourceId().toString())
					|| !template(evaluation).slots().stream().filter(value -> value.slotId().equals(selection.slotId()))
							.anyMatch(value -> value.resourceIds().contains(selection.resourceId().toString()))
							&& (mapping == null || !mapping.admits(selection))) {
				throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_POLICY_STALE",
						"The stored SupportPlan slot is no longer admitted by current template policy");
			}
			queries.add(query(slot, selection.resourceId().toString(), Long.toString(selection.contentVersion())));
		}
		return new RevalidationRequest(proposal.families(), proposal.slots(), selections,
				new ResourceEligibilityBatchRequest(List.copyOf(queries)), mapping);
	}

	Template template(EvaluationView evaluation, PersonalizationSnapshot preferences, ReassessmentSymptomMapping mapping) {
		var base = template(evaluation, preferences);
		if (mapping == null) return base;
		return new Template(base.families(), base.slots().stream().map(slot -> {
			var ids = new ArrayList<>(slot.resourceIds());
			mapping.resourceReasons().stream().filter(reason -> slot.slotId().equals(reason.slotId()))
					.map(reason -> reason.resourceId().toString()).filter(id -> !ids.contains(id)).forEach(ids::add);
			return new SlotSpec(slot.slotId(), slot.kind(), slot.targetDomain(), slot.purposeCode(), slot.requiredRole(),
					slot.instrument(), slot.screeningLevel(), slot.supportTier(), List.copyOf(ids));
		}).toList());
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
		if (request.symptomMapping() != null && request.symptomMapping().resourceReasons().stream().anyMatch(reason -> {
			var result = byRequest.get(requestId(reason.slotId(), reason.resourceId().toString()));
			return result == null || !reason.contentVersion().equals(result.contentVersion())
					|| !reason.publicationId().toString().equals(result.publicationId()) || result.role() == null
					|| !reason.role().equals(result.role().name()) || !reason.eligibilityPolicyVersion().equals(response.policyVersion());
		})) {
			throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_VERSION_STALE", "Exact checklist resource admission changed");
		}
		return new RevalidationResult(response.policyVersion(), OffsetDateTime.parse(response.resolvedAt()).toInstant());
	}

	ResourceDraft exactResource(RevalidationRequest request, ResourceEligibilityBatchResponse response,
			String slotId, UUID resourceId, long contentVersion) {
		var slot = request.slots().stream().filter(value -> value.slotId().equals(slotId))
				.findFirst().orElseThrow(() -> invalidProposal());
		var result = response.results().stream()
				.filter(value -> value.requestId().equals(requestId(slot.slotId(), resourceId.toString())))
				.findFirst().orElseThrow(() -> invalidProposal());
		if (result.outcome() != ResourceEligibilityOutcome.ELIGIBLE
				|| !Long.toString(contentVersion).equals(result.contentVersion())) {
			throw invalidProposal();
		}
		return resource(result);
	}

	private ApiException invalidProposal() {
		return new ApiException(HttpStatus.CONFLICT, "PLAN_CHANGE_RESOURCE_INADMISSIBLE",
				"The proposed exact resource version is not admitted by the current SupportPlan policy");
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

	record Template(List<FamilyDraft> families, List<SlotSpec> slots) {
		List<String> resourceIds() {
			return slots.stream().flatMap(slot -> slot.resourceIds().stream()).distinct().toList();
		}
	}
	record ProposalRequest(List<FamilyDraft> families, List<SlotSpec> slots, ResourceEligibilityBatchRequest batch) { }
	record Proposal(List<FamilyDraft> families, List<SlotDraft> slots, String resourcePolicyVersion,
			java.time.Instant resourcesResolvedAt, ReassessmentSymptomMapping symptomMapping) {
		Proposal(List<FamilyDraft> families, List<SlotDraft> slots, String resourcePolicyVersion, java.time.Instant resourcesResolvedAt) {
			this(families, slots, resourcePolicyVersion, resourcesResolvedAt, null);
		}
	}
	record FamilyDraft(String family, String targetDomain, String focusTitle,
			String focusRationale, String focusReasonCode) {
		FamilyDraft(String family, String targetDomain) {
			this(family, targetDomain, null, null, null);
		}
	}
	record FocusCopy(String title, String rationale) { }
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
			List<SelectedResource> selections, ResourceEligibilityBatchRequest batch, ReassessmentSymptomMapping symptomMapping) { }
	record RevalidationResult(String resourcePolicyVersion, java.time.Instant resourcesResolvedAt) { }
}
