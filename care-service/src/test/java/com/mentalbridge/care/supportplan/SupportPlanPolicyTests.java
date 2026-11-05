package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.EligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCategory;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityReasonCode;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityResult;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.DomainReasonCode;
import com.mentalbridge.care.support.DomainSupportPathway;
import com.mentalbridge.care.support.SafetyReasonCode;
import com.mentalbridge.care.support.ScreeningDomain;
import com.mentalbridge.care.support.SupportEvaluationV2Service.DomainContributionView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.EvaluationView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.SafetyEvidenceView;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.ActivityMode;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationSnapshot;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PreferredDaypart;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PreferredFocus;

class SupportPlanPolicyTests {

	private static final String ARTICLE_DEPRESSION = "00000000-0000-4000-8000-000000000205";
	private static final String ACTIVITY_DEPRESSION = "00000000-0000-4000-8000-000000000208";
	private static final String ARTICLE_ANXIETY = "00000000-0000-4000-8000-000000000206";
	private static final String ACTIVITY_ANXIETY = "00000000-0000-4000-8000-000000000201";
	private static final String SLEEP_ADJUNCT = "00000000-0000-4000-8000-000000000207";
	private static final String JOURNAL_ADJUNCT = "00000000-0000-4000-8000-000000000212";

	private final SupportPlanPolicy policy = new SupportPlanPolicy();

	@Test
	void composesOneDomainProposalFromOnlyTheProvidedContribution() {
		var evaluation = new EvaluationView(UUID.randomUUID(), 2, "mb-support-routing-capstone-v2", Instant.EPOCH,
				List.of(domain("PHQ9", ScreeningDomain.DEPRESSIVE_SYMPTOMS, ScreeningLevel.MILD)),
				new SafetyEvidenceView(UUID.randomUUID(), "PHQ9", "PHQ9_ITEM_9",
						SafetyStatus.NEGATIVE_SAFETY_SCREEN, "MB-SAFETY-PHQ9-001-v1",
						SafetyReasonCode.PHQ9_ITEM9_NEGATIVE),
				"SCREENING_NOT_DIAGNOSIS", "Synthetic disclaimer");
		var request = request(evaluation, null);
		var proposal = policy.compose(request, eligible(request));

		assertThat(proposal.families()).extracting(SupportPlanPolicy.FamilyDraft::family)
				.containsExactly("DEPRESSIVE_SELF_GUIDED");
		assertThat(proposal.slots()).extracting(SupportPlanPolicy.SlotDraft::slotId)
				.containsExactly("depression-psychoeducation", "depression-support-activity");
		assertThat(request.batch().requests()).extracting(query -> query.resourceId())
				.containsExactly("00000000-0000-4000-8000-000000000205",
						"00000000-0000-4000-8000-000000000208");
	}

	@Test
	void composesTwoDomainMildProposalDeterministicallyWithinBounds() {
		var request = request(evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD), null);
		assertThat(request.batch().requests()).allSatisfy(query -> assertThat(query.contentVersion()).isEqualTo("0"));
		var first = policy.compose(request, eligible(request));
		var second = policy.compose(request, eligible(request));

		assertThat(first.families()).extracting(SupportPlanPolicy.FamilyDraft::family)
				.containsExactly("DEPRESSIVE_SELF_GUIDED", "ANXIETY_SELF_GUIDED");
		assertThat(first.slots()).extracting(SupportPlanPolicy.SlotDraft::slotId)
				.containsExactly("depression-psychoeducation", "depression-support-activity",
						"anxiety-psychoeducation", "anxiety-support-activity");
		assertThat(first.slots()).hasSize(4).isEqualTo(second.slots());
	}

	@Test
	void composesProfessionalAdjunctsWithoutDuplicatingAnExactResourceVersion() {
		var request = request(evaluation(ScreeningLevel.MODERATE, ScreeningLevel.MODERATE), null);
		var proposal = policy.compose(request, eligible(request));

		assertThat(proposal.slots()).hasSize(2);
		assertThat(proposal.slots()).extracting(slot -> slot.selectedResource().key()).doesNotHaveDuplicates();
		assertThat(proposal.slots()).allMatch(slot -> slot.kind().equals("OPTIONAL"));
	}

	@Test
	void rejectsStaleUnavailableAndMissingCoreFacts() {
		var request = request(evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD), null);

		assertThatThrownBy(() -> policy.compose(request,
				response(request, ResourceEligibilityOutcome.STALE, ResourceEligibilityReasonCode.CONTENT_VERSION_STALE)))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("RESOURCE_VERSION_STALE"));
		assertThatThrownBy(() -> policy.compose(request,
				response(request, ResourceEligibilityOutcome.UNAVAILABLE, ResourceEligibilityReasonCode.DEPENDENCY_UNAVAILABLE)))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("RESOURCE_ELIGIBILITY_UNAVAILABLE"));
		assertThatThrownBy(() -> policy.compose(request,
				response(request, ResourceEligibilityOutcome.INELIGIBLE,
						ResourceEligibilityReasonCode.DOMAIN_OR_PATHWAY_NOT_ELIGIBLE)))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_CORE_UNAVAILABLE"));
	}

	@Test
	void personalizesFocusCopyAndOnlyReordersPolicyAdmittedCandidates() {
		var evaluation = new EvaluationView(UUID.randomUUID(), 2, "mb-support-routing-capstone-v2", Instant.EPOCH,
				List.of(domain("PHQ9", ScreeningDomain.DEPRESSIVE_SYMPTOMS, ScreeningLevel.MINIMAL)),
				new SafetyEvidenceView(UUID.randomUUID(), "PHQ9", "PHQ9_ITEM_9",
						SafetyStatus.NEGATIVE_SAFETY_SCREEN, "MB-SAFETY-PHQ9-001-v1",
						SafetyReasonCode.PHQ9_ITEM9_NEGATIVE),
				"SCREENING_NOT_DIAGNOSIS", "Synthetic disclaimer");
		var personalization = new PersonalizationSnapshot(UUID.randomUUID(), UUID.randomUUID(),
				SupportPlanPersonalizationService.SCHEMA_VERSION, 1, PreferredFocus.MOTIVATION, null, 10,
				PreferredDaypart.MORNING, List.of(ActivityMode.READING), List.of(), Instant.EPOCH);
		var request = request(evaluation, personalization);
		var results = request.batch().requests().stream().map(query -> new ResourceEligibilityResult(
				query.requestId(), query.resourceId(), query.contentVersion(), ResourceEligibilityOutcome.ELIGIBLE,
				ResourceEligibilityReasonCode.ELIGIBLE_MATCH, EligibilityRole.PRIMARY,
				UUID.nameUUIDFromBytes(query.requestId().getBytes()).toString(),
				query.resourceId().endsWith("205") ? ResourceCategory.ARTICLE : ResourceCategory.BREATHING,
				"Synthetic reviewed resource", "Synthetic general wellbeing content", null)).toList();

		var proposal = policy.compose(request,
				new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-10-11T00:00:00Z", results),
				personalization);

		assertThat(proposal.families()).singleElement().satisfies(family -> {
			assertThat(family.focusTitle()).isEqualTo("Bắt đầu bằng bước nhỏ");
			assertThat(family.focusReasonCode()).isEqualTo("USER_PREFERENCE_AND_CARE_POLICY");
		});
		assertThat(proposal.slots()).singleElement().satisfies(slot ->
				assertThat(slot.selectedResource().resourceId().toString()).endsWith("205"));
	}

	@Test
	void keepsDomainAwareFocusCopyWhenTheRequestedGoalHasNoAdmittedAlignedContent() {
		var evaluation = evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD);
		for (var focus : List.of(PreferredFocus.SLEEP, PreferredFocus.UNDERSTANDING_EMOTIONS)) {
			var personalization = new PersonalizationSnapshot(UUID.randomUUID(), UUID.randomUUID(),
					SupportPlanPersonalizationService.SCHEMA_VERSION, 1, focus, null, 10,
					PreferredDaypart.EVENING, List.of(), List.of(), Instant.EPOCH);

			var request = request(evaluation, personalization);
			var proposal = policy.compose(request, eligible(request), personalization);

			assertThat(proposal.families()).allSatisfy(family -> {
				assertThat(family.focusTitle()).isNull();
				assertThat(family.focusRationale()).isNull();
				assertThat(family.focusReasonCode()).isNull();
			});
		}
	}

	@Test
	void queriesEachCandidateAtItsDiscoveredCurrentVersionAndPersistsThatExactVersion() {
		var template = policy.template(evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD));
		assertThat(template.resourceIds()).containsExactly(ARTICLE_DEPRESSION, ACTIVITY_DEPRESSION, ARTICLE_ANXIETY,
				ACTIVITY_ANXIETY);
		var versions = new HashMap<String, String>(versions(template, "0"));
		versions.put(ACTIVITY_ANXIETY, "1");
		var request = policy.request(template, versions);

		assertThat(request.batch().requests()).extracting(query -> query.resourceId() + "@" + query.contentVersion())
				.containsExactly(ARTICLE_DEPRESSION + "@0", ACTIVITY_DEPRESSION + "@0", ARTICLE_ANXIETY + "@0",
						ACTIVITY_ANXIETY + "@1");
		var proposal = policy.compose(request, eligible(request));
		assertThat(proposal.slots()).filteredOn(slot -> slot.slotId().equals("anxiety-support-activity"))
				.singleElement().satisfies(slot -> assertThat(slot.selectedResource().key())
						.isEqualTo(ACTIVITY_ANXIETY + ":1"));
	}

	@Test
	void choosesAnAdmittedAlternativeWhenTheCurrentVersionHasNoEligibilityPublication() {
		var request = request(evaluation(ScreeningLevel.MINIMAL, ScreeningLevel.MINIMAL), null);
		var results = new ArrayList<>(eligible(request).results());
		results.replaceAll(result -> result.resourceId().equals(ACTIVITY_ANXIETY)
				? ineligible(result, ResourceEligibilityReasonCode.NO_ELIGIBILITY_PUBLICATION) : result);

		var proposal = policy.compose(request,
				new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-19T00:00:00Z", results));

		assertThat(proposal.slots()).filteredOn(slot -> slot.slotId().equals("anxiety-maintenance"))
				.singleElement().satisfies(slot -> {
					assertThat(slot.selectedResource().resourceId().toString()).isEqualTo(ARTICLE_ANXIETY);
					assertThat(slot.allowedAlternatives()).isEmpty();
				});
	}

	@Test
	void treatsAKnownMissingResourceAsAbsentAndAnUnansweredCandidateAsUncertainty() {
		var template = policy.template(evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD));
		var missingCore = new HashMap<String, String>(versions(template, "0"));
		missingCore.put(ACTIVITY_ANXIETY, null);
		var request = policy.request(template, missingCore);
		assertThat(request.batch().requests()).extracting(query -> query.resourceId()).doesNotContain(ACTIVITY_ANXIETY);
		assertThatThrownBy(() -> policy.compose(request, eligible(request)))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_CORE_UNAVAILABLE"));

		var unanswered = new HashMap<String, String>(versions(template, "0"));
		unanswered.remove(ACTIVITY_ANXIETY);
		assertThatThrownBy(() -> policy.request(template, unanswered))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("RESOURCE_ELIGIBILITY_UNAVAILABLE"));
	}

	@Test
	void dropsOnlyAnOptionalSlotWhoseCandidatesAreAllKnownMissing() {
		var template = policy.template(evaluation(ScreeningLevel.MODERATE, ScreeningLevel.MODERATE));
		var versions = new HashMap<String, String>(versions(template, "0"));
		for (String id : List.of(SLEEP_ADJUNCT, JOURNAL_ADJUNCT, ACTIVITY_ANXIETY)) versions.put(id, null);
		var request = policy.request(template, versions);

		var proposal = policy.compose(request, eligible(request));

		assertThat(proposal.slots()).singleElement().satisfies(slot -> {
			assertThat(slot.slotId()).isEqualTo("anxiety-adjunct");
			assertThat(slot.selectedResource().resourceId().toString()).isEqualTo(ACTIVITY_DEPRESSION);
		});
	}

	@Test
	void legacyKeepsItsOneResourceBoundWhileNativeDropsEveryUnfilledOptionalSlot() {
		var template = policy.template(evaluation(ScreeningLevel.MODERATE, ScreeningLevel.MODERATE));
		var request = policy.request(template, template.resourceIds().stream()
				.collect(HashMap<String, String>::new, (map, id) -> map.put(id, null), HashMap::putAll));
		assertThat(request.batch().requests()).isEmpty();
		var empty = new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-19T00:00:00Z", List.of());

		assertThatThrownBy(() -> policy.compose(request, empty))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_BOUNDS_UNSATISFIED"));
		var proposal = policy.composeNative(request, empty, null);
		assertThat(proposal.slots()).isEmpty();
		assertThat(proposal.families()).hasSize(2);

		var core = request(evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD), null);
		var noCore = new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-19T00:00:00Z",
				eligible(core).results().stream().map(result ->
						ineligible(result, ResourceEligibilityReasonCode.NO_ELIGIBILITY_PUBLICATION)).toList());
		assertThatThrownBy(() -> policy.composeNative(core, noCore, null))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_CORE_UNAVAILABLE"));
	}

	@Test
	void failsTheWholeProposalWhenAnOptionalCandidateIsStale() {
		var request = request(evaluation(ScreeningLevel.MILD, ScreeningLevel.MODERATE), null);
		var results = new ArrayList<>(eligible(request).results());
		results.replaceAll(result -> result.resourceId().equals(SLEEP_ADJUNCT)
				? new ResourceEligibilityResult(result.requestId(), result.resourceId(), result.contentVersion(),
						ResourceEligibilityOutcome.STALE, ResourceEligibilityReasonCode.CONTENT_VERSION_STALE,
						null, null, null, null, null, null)
				: result);

		assertThatThrownBy(() -> policy.compose(request,
				new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-19T00:00:00Z", results)))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("RESOURCE_VERSION_STALE"));
	}

	private SupportPlanPolicy.ProposalRequest request(EvaluationView evaluation, PersonalizationSnapshot personalization) {
		var template = policy.template(evaluation, personalization);
		return policy.request(template, versions(template, "0"));
	}

	private Map<String, String> versions(SupportPlanPolicy.Template template, String version) {
		return template.resourceIds().stream().collect(Collectors.toMap(id -> id, id -> version));
	}

	private ResourceEligibilityResult ineligible(ResourceEligibilityResult result, ResourceEligibilityReasonCode reason) {
		return new ResourceEligibilityResult(result.requestId(), result.resourceId(), result.contentVersion(),
				ResourceEligibilityOutcome.INELIGIBLE, reason, null, null, null, null, null, null);
	}

	private EvaluationView evaluation(ScreeningLevel depression, ScreeningLevel anxiety) {
		return new EvaluationView(UUID.randomUUID(), 2, "mb-support-routing-capstone-v2", Instant.EPOCH,
				List.of(domain("PHQ9", ScreeningDomain.DEPRESSIVE_SYMPTOMS, depression),
						domain("GAD7", ScreeningDomain.ANXIETY_SYMPTOMS, anxiety)),
				new SafetyEvidenceView(UUID.randomUUID(), "PHQ9", "PHQ9_ITEM_9",
						SafetyStatus.NEGATIVE_SAFETY_SCREEN, "MB-SAFETY-PHQ9-001-v1",
						SafetyReasonCode.PHQ9_ITEM9_NEGATIVE),
				"SCREENING_NOT_DIAGNOSIS", "Synthetic disclaimer");
	}

	private DomainContributionView domain(String instrument, ScreeningDomain domain, ScreeningLevel level) {
		var pathway = level == ScreeningLevel.MINIMAL || level == ScreeningLevel.MILD
				? DomainSupportPathway.SELF_GUIDED_SUPPORT : DomainSupportPathway.PROFESSIONAL_SUPPORT_RECOMMENDED;
		var reason = DomainReasonCode.valueOf(instrument + "_LEVEL_" + level.name());
		return new DomainContributionView(UUID.randomUUID(), UUID.randomUUID(), instrument, domain,
				"questionnaire-v1", instrument.toLowerCase() + "-scoring-v1", level, pathway, List.of(reason));
	}

	private ResourceEligibilityBatchResponse eligible(SupportPlanPolicy.ProposalRequest request) {
		return response(request, ResourceEligibilityOutcome.ELIGIBLE, ResourceEligibilityReasonCode.ELIGIBLE_MATCH);
	}

	private ResourceEligibilityBatchResponse response(SupportPlanPolicy.ProposalRequest request,
			ResourceEligibilityOutcome outcome, ResourceEligibilityReasonCode reason) {
		var results = request.batch().requests().stream().map(query -> new ResourceEligibilityResult(query.requestId(),
				query.resourceId(), query.contentVersion(), outcome, reason,
				outcome == ResourceEligibilityOutcome.ELIGIBLE
						? query.requiredRole().name().equals("PRIMARY") ? EligibilityRole.PRIMARY : EligibilityRole.ADJUNCT
						: null,
				outcome == ResourceEligibilityOutcome.ELIGIBLE ? UUID.nameUUIDFromBytes(query.requestId().getBytes()).toString() : null,
				outcome == ResourceEligibilityOutcome.ELIGIBLE ? ResourceCategory.ARTICLE : null,
				outcome == ResourceEligibilityOutcome.ELIGIBLE ? "Synthetic reviewed resource" : null,
				outcome == ResourceEligibilityOutcome.ELIGIBLE ? "Synthetic general wellbeing content" : null,
				null)).toList();
		return new ResourceEligibilityBatchResponse("content-eligibility-v1", "2026-09-19T00:00:00Z", results);
	}
}
