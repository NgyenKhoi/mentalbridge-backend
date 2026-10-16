package com.mentalbridge.care.guidedjourney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.EntitlementSource;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.entitlement.EntitlementClient;
import com.mentalbridge.care.guidedjourney.GuidedJourneyService.EntitlementStatus;
import com.mentalbridge.care.guidedjourney.GuidedJourneyService.NextAction;
import com.mentalbridge.care.guidedjourney.GuidedJourneyService.Stage;
import com.mentalbridge.care.screeningepisode.ScreeningEpisodeService;
import com.mentalbridge.care.screeningepisode.ScreeningEpisodeService.EpisodeView;
import com.mentalbridge.care.screeningepisode.ScreeningEpisodeService.JourneyContext;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.DomainReasonCode;
import com.mentalbridge.care.support.DomainSupportPathway;
import com.mentalbridge.care.support.SafetyReasonCode;
import com.mentalbridge.care.support.ScreeningDomain;
import com.mentalbridge.care.support.SupportEvaluationV2Service;
import com.mentalbridge.care.support.SupportEvaluationV2Service.DomainContributionView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.EvaluationView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.SafetyEvidenceView;
import com.mentalbridge.care.supportguide.SupportGuideService;
import com.mentalbridge.care.supportplan.SupportPlanV3Service;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanStatusView;

class GuidedJourneyServiceTests {

	private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000668");
	private static final UUID EPISODE_ID = UUID.fromString("20000000-0000-4000-8000-000000000668");
	private static final UUID SUPPORT_EVALUATION_ID = UUID.fromString("30000000-0000-4000-8000-000000000668");
	private static final UUID PRESENTATION_ID = UUID.fromString("40000000-0000-4000-8000-000000000668");
	private static final UUID GUIDE_ID = UUID.fromString("50000000-0000-4000-8000-000000000668");
	private static final UUID PLAN_ID = UUID.fromString("60000000-0000-4000-8000-000000000668");
	private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");

	private final ScreeningEpisodeService episodes = mock(ScreeningEpisodeService.class);
	private final SupportGuideService guides = mock(SupportGuideService.class);
	private final SupportEvaluationV2Service evaluations = mock(SupportEvaluationV2Service.class);
	private final SupportPlanV3Service plans = mock(SupportPlanV3Service.class);
	private final EntitlementClient entitlements = mock(EntitlementClient.class);
	private final GuidedJourneyService service = new GuidedJourneyService(episodes, guides, evaluations, plans,
			entitlements);

	@Test
	void requiresAssessmentWithoutACompletedInitialCheckAndNeverChecksEntitlement() {
		when(episodes.journeyContext(USER_ID, "INITIAL_CHECK")).thenReturn(new JourneyContext(Optional.empty(), true));

		var journey = service.current(USER_ID, "token", UUID.randomUUID());

		assertThat(journey.stage()).isEqualTo(Stage.ASSESSMENT_REQUIRED);
		assertThat(journey.assessmentInProgress()).isTrue();
		assertThat(journey.episodeId()).isNull();
		assertThat(journey.nextAction()).isNull();
		assertThat(journey.entitlementStatus()).isEqualTo(EntitlementStatus.NOT_REQUIRED);
		verifyNoInteractions(entitlements, guides, plans, evaluations);
	}

	@Test
	void safetyEvidenceInterruptsEveryOrdinaryActionIncludingAnExistingPlan() {
		completedEpisode(true, DomainSupportPathway.PROFESSIONAL_SUPPORT_RECOMMENDED);
		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.of(new PlanStatusView(PLAN_ID, "ACTIVE")));

		var journey = service.current(USER_ID, "token", UUID.randomUUID());

		assertThat(journey.nextAction()).isEqualTo(NextAction.REVIEW_SAFETY_SUPPORT);
		assertThat(journey.currentSupportPlanId()).isEqualTo(PLAN_ID);
		verifyNoInteractions(entitlements);
	}

	@Test
	void storedProfessionalPathwayPrecedesPlanControls() {
		completedEpisode(false, DomainSupportPathway.PROFESSIONAL_SUPPORT_RECOMMENDED);
		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.of(new PlanStatusView(PLAN_ID, "PAUSED")));

		assertThat(service.current(USER_ID, "token", UUID.randomUUID()).nextAction())
				.isEqualTo(NextAction.FIND_SPECIALIST);
		verifyNoInteractions(entitlements);
	}

	@Test
	void existingCurrentPlanOrDraftIsReopenedInsteadOfProposingAnotherPlan() {
		completedEpisode(false, DomainSupportPathway.SELF_GUIDED_SUPPORT);
		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.of(new PlanStatusView(PLAN_ID, "PAUSED")));
		assertThat(service.current(USER_ID, "token", UUID.randomUUID()).nextAction())
				.isEqualTo(NextAction.OPEN_CURRENT_PLAN);

		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.of(new PlanStatusView(PLAN_ID, "DRAFT")));
		var journey = service.current(USER_ID, "token", UUID.randomUUID());

		assertThat(journey.nextAction()).isEqualTo(NextAction.REVIEW_PLAN_DRAFT);
		assertThat(journey.entitlementStatus()).isEqualTo(EntitlementStatus.NOT_REQUIRED);
		verifyNoInteractions(entitlements);
	}

	@Test
	void paidPackageWithoutPlanEntersProposalWhileFreeContinuesWithGuideResources() {
		completedEpisode(false, DomainSupportPathway.SELF_GUIDED_SUPPORT);
		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.empty());

		when(entitlements.current(any(), any(), any())).thenReturn(entitlement(ServicePackage.PLUS));
		var paid = service.current(USER_ID, "token", UUID.randomUUID());
		assertThat(paid.nextAction()).isEqualTo(NextAction.START_PLAN_PROPOSAL);
		assertThat(paid.entitlementStatus()).isEqualTo(EntitlementStatus.VERIFIED);

		when(entitlements.current(any(), any(), any())).thenReturn(entitlement(ServicePackage.FREE));
		var free = service.current(USER_ID, "token", UUID.randomUUID());
		assertThat(free.nextAction()).isEqualTo(NextAction.EXPLORE_GUIDE_RESOURCES);
		assertThat(free.entitlementStatus()).isEqualTo(EntitlementStatus.VERIFIED);
	}

	@Test
	void unverifiableEntitlementFailsClosedToTheAlwaysAvailableResourceStep() {
		completedEpisode(false, DomainSupportPathway.SELF_GUIDED_SUPPORT);
		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.empty());
		when(entitlements.current(any(), any(), any())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
				"ENTITLEMENT_UNAVAILABLE", "Current service entitlement could not be verified"));

		var journey = service.current(USER_ID, "token", UUID.randomUUID());

		assertThat(journey.nextAction()).isEqualTo(NextAction.EXPLORE_GUIDE_RESOURCES);
		assertThat(journey.entitlementStatus()).isEqualTo(EntitlementStatus.UNAVAILABLE);
		assertThat(journey.stage()).isEqualTo(Stage.GUIDE_READY);
	}

	@Test
	void unexpectedEntitlementFailureIsNotHiddenAsAFreeOutcome() {
		completedEpisode(false, DomainSupportPathway.SELF_GUIDED_SUPPORT);
		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.empty());
		when(entitlements.current(any(), any(), any())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED,
				"UNAUTHENTICATED", "Authenticated account identifier is invalid"));

		assertThatThrownBy(() -> service.current(USER_ID, "token", UUID.randomUUID()))
				.isInstanceOf(ApiException.class).hasMessageContaining("Authenticated");
	}

	@Test
	void reportsTheCompletedEpisodeContextAndMissingGuideWithoutCreatingIt() {
		completedEpisode(false, DomainSupportPathway.SELF_GUIDED_SUPPORT);
		when(guides.guideIdForEvaluation(USER_ID, SUPPORT_EVALUATION_ID)).thenReturn(Optional.empty());
		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.empty());
		when(entitlements.current(any(), any(), any())).thenReturn(entitlement(ServicePackage.FREE));

		var journey = service.current(USER_ID, "token", UUID.randomUUID());

		assertThat(journey.stage()).isEqualTo(Stage.GUIDE_REQUIRED);
		assertThat(journey.episodeId()).isEqualTo(EPISODE_ID);
		assertThat(journey.presentationEvaluationId()).isEqualTo(PRESENTATION_ID);
		assertThat(journey.supportGuideId()).isNull();
		assertThat(journey.journeyPolicyVersion()).isEqualTo("mb-guided-journey-v1");
		verify(guides, org.mockito.Mockito.never()).ensureForEpisode(any(), any(), any(), any(), any());
	}

	@Test
	void ensuresTheGuideFromTheEpisodeExactPersistedEvaluation() {
		when(episodes.requiredCompletedEpisode(USER_ID, EPISODE_ID)).thenReturn(episode());
		when(episodes.latestCompleted(USER_ID, "INITIAL_CHECK")).thenReturn(Optional.of(episode()));
		var correlationId = UUID.randomUUID();

		service.ensureGuide(USER_ID, "token", correlationId, EPISODE_ID);

		verify(guides).ensureForEpisode(USER_ID, "token", correlationId, EPISODE_ID, SUPPORT_EVALUATION_ID);
	}

	@Test
	void neverPreparesAGuideForAHistoricalOrReassessmentEpisode() {
		var historical = UUID.fromString("70000000-0000-4000-8000-000000000668");
		when(episodes.requiredCompletedEpisode(USER_ID, historical)).thenReturn(new EpisodeView(historical,
				"REASSESSMENT", "COMPLETED", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, NOW, NOW,
				NOW, 3));
		when(episodes.latestCompleted(USER_ID, "INITIAL_CHECK")).thenReturn(Optional.of(episode()));

		assertThatThrownBy(() -> service.ensureGuide(USER_ID, "token", UUID.randomUUID(), historical))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("SCREENING_EPISODE_NOT_CURRENT"));
		verify(guides, org.mockito.Mockito.never()).ensureForEpisode(any(), any(), any(), any(), any());
	}

	@Test
	void staleSupportPolicyNeverOffersAProposalThatCareWouldReject() {
		completedEpisode(false, DomainSupportPathway.SELF_GUIDED_SUPPORT);
		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.empty());
		when(evaluations.getCurrentCompatible(USER_ID, SUPPORT_EVALUATION_ID)).thenThrow(new ApiException(
				HttpStatus.CONFLICT, "SUPPORT_EVALUATION_STALE", "Support evaluation policy is no longer current"));

		var journey = service.current(USER_ID, "token", UUID.randomUUID());

		assertThat(journey.nextAction()).isEqualTo(NextAction.EXPLORE_GUIDE_RESOURCES);
		assertThat(journey.entitlementStatus()).isEqualTo(EntitlementStatus.NOT_REQUIRED);
		verifyNoInteractions(entitlements);
	}

	@Test
	void missingPublishedPolicyIsSurfacedInsteadOfDowngradedToResources() {
		completedEpisode(false, DomainSupportPathway.SELF_GUIDED_SUPPORT);
		when(plans.currentOrDraftStatus(USER_ID)).thenReturn(Optional.empty());
		when(evaluations.getCurrentCompatible(USER_ID, SUPPORT_EVALUATION_ID)).thenThrow(new ApiException(
				HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_POLICY_UNAVAILABLE",
				"No published domain-aware support policy is available"));

		assertThatThrownBy(() -> service.current(USER_ID, "token", UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("SUPPORT_POLICY_UNAVAILABLE"));
		verifyNoInteractions(entitlements);
	}

	private void completedEpisode(boolean positiveSafety, DomainSupportPathway gad7Pathway) {
		when(episodes.journeyContext(USER_ID, "INITIAL_CHECK")).thenReturn(new JourneyContext(Optional.of(episode()), false));
		when(guides.guideIdForEvaluation(USER_ID, SUPPORT_EVALUATION_ID)).thenReturn(Optional.of(GUIDE_ID));
		when(evaluations.get(USER_ID, SUPPORT_EVALUATION_ID)).thenReturn(evaluation(positiveSafety, gad7Pathway));
	}

	private EpisodeView episode() {
		return new EpisodeView(EPISODE_ID, "INITIAL_CHECK", "COMPLETED", UUID.randomUUID(), UUID.randomUUID(),
				SUPPORT_EVALUATION_ID, PRESENTATION_ID, NOW, NOW, NOW, 3);
	}

	private EvaluationView evaluation(boolean positiveSafety, DomainSupportPathway gad7Pathway) {
		var phq9Id = UUID.randomUUID();
		var domains = List.of(
				new DomainContributionView(phq9Id, UUID.randomUUID(), "PHQ9", ScreeningDomain.DEPRESSIVE_SYMPTOMS,
						"phq9-v2", "phq9-standard-bands-v1", ScreeningLevel.MILD,
						DomainSupportPathway.SELF_GUIDED_SUPPORT, List.of(DomainReasonCode.PHQ9_LEVEL_MILD)),
				new DomainContributionView(UUID.randomUUID(), UUID.randomUUID(), "GAD7",
						ScreeningDomain.ANXIETY_SYMPTOMS, "gad7-v1", "gad7-standard-bands-v1", ScreeningLevel.MILD,
						gad7Pathway, List.of(DomainReasonCode.GAD7_LEVEL_MILD)));
		var safety = new SafetyEvidenceView(phq9Id, "PHQ9", "PHQ9_ITEM_9",
				positiveSafety ? SafetyStatus.POSITIVE_SAFETY_SCREEN : SafetyStatus.NEGATIVE_SAFETY_SCREEN,
				"MB-SAFETY-PHQ9-001-v1",
				positiveSafety ? SafetyReasonCode.PHQ9_ITEM9_POSITIVE : SafetyReasonCode.PHQ9_ITEM9_NEGATIVE);
		return new EvaluationView(SUPPORT_EVALUATION_ID, 2, "mb-support-routing-capstone-v2", NOW, domains, safety,
				"SCREENING_NOT_DIAGNOSIS", "Not a diagnosis.");
	}

	private CurrentEntitlementResponse entitlement(ServicePackage servicePackage) {
		boolean free = servicePackage == ServicePackage.FREE;
		return new CurrentEntitlementResponse(USER_ID, servicePackage,
				free ? EntitlementSource.DEFAULT_FREE : EntitlementSource.DEMO, free ? null : "demo-668",
				free ? null : NOW, free ? null : NOW.plusSeconds(86400), "service-entitlement-v1", 1, NOW);
	}
}
