package com.mentalbridge.care.supportguide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.EligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCategory;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityReasonCode;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityResult;
import com.mentalbridge.care.support.DomainReasonCode;
import com.mentalbridge.care.support.DomainSupportPathway;
import com.mentalbridge.care.support.SafetyReasonCode;
import com.mentalbridge.care.support.ScreeningDomain;
import com.mentalbridge.care.support.SupportEvaluationV2Service;
import com.mentalbridge.care.support.SupportEvaluationV2Service.DomainContributionView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.EvaluationView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.SafetyEvidenceView;
import com.mentalbridge.care.supportguide.SupportGuideWriter.StoredGuide;

class SupportGuideServiceTests {

	private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000511");
	private static final UUID PHQ9_ID = UUID.fromString("20000000-0000-4000-8000-000000000511");
	private static final UUID GAD7_ID = UUID.fromString("30000000-0000-4000-8000-000000000511");
	private static final UUID EVALUATION_ID = UUID.fromString("40000000-0000-4000-8000-000000000511");
	private static final Instant NOW = Instant.parse("2026-09-17T06:00:00Z");

	@Test
	void keepsPositiveSafetyAndApprovedCopyAvailableWhenResourcesAreUnavailable() {
		var view = generate(ResourceEligibilityOutcome.UNAVAILABLE, true);

		assertThat(view.safety().status()).isEqualTo("POSITIVE_SAFETY_SCREEN");
		assertThat(view.safety().guidance()).isNotBlank();
		assertThat(view.resourceResolution().status()).isEqualTo("UNAVAILABLE");
		assertThat(view.resources()).isEmpty();
		assertThat(view.phrasing().status()).isEqualTo("AI_UNAVAILABLE_FALLBACK");
	}

	@Test
	void returnsExactReviewedSnapshotsForAnAvailableNormalGuide() {
		var view = generate(ResourceEligibilityOutcome.ELIGIBLE, false);

		assertThat(view.safety().status()).isEqualTo("NEGATIVE_SAFETY_SCREEN");
		assertThat(view.resourceResolution().status()).isEqualTo("AVAILABLE");
		assertThat(view.resources()).hasSize(4).allSatisfy(resource -> {
			assertThat(resource.contentVersion()).isEqualTo("0");
			assertThat(resource.publicationId()).isNotNull();
			assertThat(resource.title()).isEqualTo("Reviewed resource");
		});
		assertThat(view.guideType()).isEqualTo("ONE_TIME_SUPPORT_GUIDE");
	}

	@Test
	void returnsStableEmptyAndStaleOutcomesWithoutResources() {
		assertThat(generate(ResourceEligibilityOutcome.INELIGIBLE, false).resourceResolution().status())
				.isEqualTo("EMPTY");
		assertThat(generate(ResourceEligibilityOutcome.STALE, false).resourceResolution().status())
				.isEqualTo("STALE");
	}

	private SupportGuideService.SupportGuideView generate(ResourceEligibilityOutcome outcome, boolean positive) {
		var evaluations = mock(SupportEvaluationV2Service.class);
		var eligibility = mock(ResourceEligibilityClient.class);
		var writer = mock(SupportGuideWriter.class);
		var evaluation = evaluation(positive);
		when(evaluations.evaluate(eq(USER_ID), anyString(), any(), any())).thenReturn(evaluation);
		when(eligibility.resolve(any(), eq("user-token"), any())).thenAnswer(invocation -> {
			var request = (com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest) invocation.getArgument(0);
			var results = request.requests().stream().map(query -> result(query.requestId(), query.resourceId(), outcome))
					.toList();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", NOW.toString(), results);
		});
		when(writer.persist(eq(USER_ID), anyString(), anyString(), any())).thenAnswer(invocation -> {
			var draft = (SupportGuideWriter.Draft) invocation.getArgument(3);
			var guide = new SupportGuideEntity(UUID.randomUUID(), USER_ID, draft.supportEvaluationId(),
					SupportGuideService.GUIDE_POLICY_VERSION, draft.generatedAt(), draft.explanationCode(),
					draft.explanationText(), draft.safetyStatus(), draft.safetyReasonCode(), draft.safetyPolicyVersion(),
					draft.safetyGuidanceCode(), draft.safetyGuidance(), draft.resourceStatus(),
					draft.resourcePolicyVersion(), draft.resourcesResolvedAt(), draft.phrasingStatus());
			short[] ordinal = { 1 };
			var resources = draft.resources().stream().map(resource -> new SupportGuideResourceEntity(UUID.randomUUID(),
					guide.id(), ordinal[0]++, resource.resourceId(), resource.contentVersion(), resource.publicationId(),
					resource.domain(), resource.role(), resource.category(), resource.title(), resource.summary(),
					resource.externalUrl())).toList();
			return new StoredGuide(guide, resources);
		});

		var service = new SupportGuideService(evaluations, eligibility, writer,
				Clock.fixed(NOW, ZoneOffset.UTC));
		return service.generate(USER_ID, "user-token", "support-guide-unit-0001", UUID.randomUUID(),
				new SupportGuideService.GenerateCommand(PHQ9_ID, GAD7_ID));
	}

	private EvaluationView evaluation(boolean positive) {
		var domains = List.of(
				new DomainContributionView(PHQ9_ID, UUID.randomUUID(), "PHQ9", ScreeningDomain.DEPRESSIVE_SYMPTOMS,
						"phq9-v2", "phq9-standard-bands-v1", ScreeningLevel.MILD,
						DomainSupportPathway.SELF_GUIDED_SUPPORT, List.of(DomainReasonCode.PHQ9_LEVEL_MILD)),
				new DomainContributionView(GAD7_ID, UUID.randomUUID(), "GAD7", ScreeningDomain.ANXIETY_SYMPTOMS,
						"gad7-v1", "gad7-standard-bands-v1", ScreeningLevel.MILD,
						DomainSupportPathway.SELF_GUIDED_SUPPORT, List.of(DomainReasonCode.GAD7_LEVEL_MILD)));
		var safety = new SafetyEvidenceView(PHQ9_ID, "PHQ9", "PHQ9_ITEM_9",
				positive ? SafetyStatus.POSITIVE_SAFETY_SCREEN : SafetyStatus.NEGATIVE_SAFETY_SCREEN,
				"MB-SAFETY-PHQ9-001-v1",
				positive ? SafetyReasonCode.PHQ9_ITEM9_POSITIVE : SafetyReasonCode.PHQ9_ITEM9_NEGATIVE);
		return new EvaluationView(EVALUATION_ID, 2, "mb-support-routing-capstone-v2", NOW, domains, safety,
				"SCREENING_NOT_DIAGNOSIS", "Not a diagnosis.");
	}

	private ResourceEligibilityResult result(String requestId, String resourceId,
			ResourceEligibilityOutcome outcome) {
		if (outcome == ResourceEligibilityOutcome.ELIGIBLE) {
			return new ResourceEligibilityResult(requestId, resourceId, "0", outcome,
					ResourceEligibilityReasonCode.ELIGIBLE_MATCH, EligibilityRole.PRIMARY,
					UUID.randomUUID().toString(), ResourceCategory.ARTICLE, "Reviewed resource",
					"Approved immutable summary.", null);
		}
		var reason = switch (outcome) {
			case UNAVAILABLE -> ResourceEligibilityReasonCode.DEPENDENCY_UNAVAILABLE;
			case STALE -> ResourceEligibilityReasonCode.CONTENT_VERSION_STALE;
			default -> ResourceEligibilityReasonCode.DOMAIN_OR_PATHWAY_NOT_ELIGIBLE;
		};
		return new ResourceEligibilityResult(requestId, resourceId, "0", outcome, reason,
				null, null, null, null, null, null);
	}
}
