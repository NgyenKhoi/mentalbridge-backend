package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

class SupportPlanPolicyTests {

	private final SupportPlanPolicy policy = new SupportPlanPolicy();

	@Test
	void composesOneDomainProposalFromOnlyTheProvidedContribution() {
		var evaluation = new EvaluationView(UUID.randomUUID(), 2, "mb-support-routing-capstone-v2", Instant.EPOCH,
				List.of(domain("PHQ9", ScreeningDomain.DEPRESSIVE_SYMPTOMS, ScreeningLevel.MILD)),
				new SafetyEvidenceView(UUID.randomUUID(), "PHQ9", "PHQ9_ITEM_9",
						SafetyStatus.NEGATIVE_SAFETY_SCREEN, "MB-SAFETY-PHQ9-001-v1",
						SafetyReasonCode.PHQ9_ITEM9_NEGATIVE),
				"SCREENING_NOT_DIAGNOSIS", "Synthetic disclaimer");
		var request = policy.request(evaluation);
		var proposal = policy.compose(request, eligible(request));

		assertThat(proposal.families()).extracting(SupportPlanPolicy.FamilyDraft::family)
				.containsExactly("DEPRESSIVE_SELF_GUIDED");
		assertThat(proposal.slots()).extracting(SupportPlanPolicy.SlotDraft::slotId)
				.containsExactly("depression-psychoeducation", "depression-support-activity");
	}

	@Test
	void composesTwoDomainMildProposalDeterministicallyWithinBounds() {
		var request = policy.request(evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD));
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
		var request = policy.request(evaluation(ScreeningLevel.MODERATE, ScreeningLevel.MODERATE));
		var proposal = policy.compose(request, eligible(request));

		assertThat(proposal.slots()).hasSize(2);
		assertThat(proposal.slots()).extracting(slot -> slot.selectedResource().key()).doesNotHaveDuplicates();
		assertThat(proposal.slots()).allMatch(slot -> slot.kind().equals("OPTIONAL"));
	}

	@Test
	void rejectsStaleUnavailableAndMissingCoreFacts() {
		var request = policy.request(evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD));

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
