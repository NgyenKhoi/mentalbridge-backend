package com.mentalbridge.care.supportguide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.EligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.RequiredEligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCategory;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
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
import com.mentalbridge.care.supportguide.SupportGuideResourceMap.Candidate;
import com.mentalbridge.care.supportguide.SupportGuideWriter.StoredGuide;
import com.mentalbridge.care.supportguide.SupportGuidePhrasingClient.Phrasing;

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
		assertThat(view.resources()).hasSize(2).allSatisfy(resource -> {
			assertThat(resource.contentVersion()).isEqualTo("0");
			assertThat(resource.publicationId()).isNotNull();
			assertThat(resource.title()).isEqualTo("Reviewed resource");
		});
		assertThat(view.guideType()).isEqualTo("ONE_TIME_SUPPORT_GUIDE");
	}

	@Test
	void persistsAiPhrasingWhenTheBoundedProviderSucceeds() {
		var view = generate(ResourceEligibilityOutcome.ELIGIBLE, false, true);

		assertThat(view.explanation().text()).isEqualTo("Diễn giải AI đã được kiểm soát.");
		assertThat(view.phrasing().status()).isEqualTo("STANDARD");
	}

	@Test
	void returnsStableEmptyAndStaleOutcomesWithoutResources() {
		assertThat(generate(ResourceEligibilityOutcome.INELIGIBLE, false).resourceResolution().status())
				.isEqualTo("EMPTY");
		assertThat(generate(ResourceEligibilityOutcome.STALE, false).resourceResolution().status())
				.isEqualTo("STALE");
	}

	@Test
	void ensureReturnsTheExistingEpisodeGuideWithoutContactingContentAiOrReevaluating() {
		var evaluations = mock(SupportEvaluationV2Service.class);
		var eligibility = mock(ResourceEligibilityClient.class);
		var phrasing = mock(SupportGuidePhrasingClient.class);
		var writer = mock(SupportGuideWriter.class);
		var guide = new SupportGuideEntity(UUID.randomUUID(), USER_ID, EVALUATION_ID,
				SupportGuideService.GUIDE_POLICY_VERSION, NOW, "STANDARD_POST_SCREENING_GUIDANCE", "Approved copy.",
				"NEGATIVE_SAFETY_SCREEN", "PHQ9_ITEM9_NEGATIVE", "MB-SAFETY-PHQ9-001-v1", "STANDARD_SAFETY_REMINDER",
				"Standard safety.", "AVAILABLE", "content-eligibility-v1", NOW, "AI_UNAVAILABLE_FALLBACK");
		when(writer.forEvaluation(USER_ID, EVALUATION_ID)).thenReturn(new StoredGuide(guide, List.of()));
		when(evaluations.get(USER_ID, EVALUATION_ID)).thenReturn(evaluation(false));
		var service = new SupportGuideService(evaluations, eligibility, phrasing, writer,
				Clock.fixed(NOW, ZoneOffset.UTC));

		var view = service.ensureForEpisode(USER_ID, "user-token", UUID.randomUUID(), UUID.randomUUID(),
				EVALUATION_ID);

		assertThat(view.supportGuideId()).isEqualTo(guide.id());
		verifyNoInteractions(eligibility, phrasing);
		verify(evaluations, never()).evaluate(any(), anyString(), any(), any());
		verify(writer, never()).persist(any(), anyString(), anyString(), any());
	}

	@Test
	void ensureMaterializesFromTheEpisodeExactEvaluationWithADeterministicEpisodeKey() {
		var episodeId = UUID.fromString("50000000-0000-4000-8000-000000000668");
		var evaluations = mock(SupportEvaluationV2Service.class);
		var eligibility = mock(ResourceEligibilityClient.class);
		var phrasing = mock(SupportGuidePhrasingClient.class);
		var writer = mock(SupportGuideWriter.class);
		when(evaluations.get(USER_ID, EVALUATION_ID)).thenReturn(evaluation(false));
		stubCollaborators(eligibility, phrasing, writer, resourceId -> ResourceEligibilityOutcome.ELIGIBLE, false);
		var service = new SupportGuideService(evaluations, eligibility, phrasing, writer,
				Clock.fixed(NOW, ZoneOffset.UTC));

		var view = service.ensureForEpisode(USER_ID, "user-token", UUID.randomUUID(), episodeId, EVALUATION_ID);

		assertThat(view.supportEvaluationId()).isEqualTo(EVALUATION_ID);
		verify(writer).persist(eq(USER_ID), eq("episode guide " + episodeId), anyString(), any());
		verify(evaluations, never()).evaluate(any(), anyString(), any(), any());
	}

	static Stream<Arguments> legalPairs() {
		return Stream.of(ScreeningLevel.MINIMAL, ScreeningLevel.MILD, ScreeningLevel.MODERATE,
				ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.SEVERE)
				.flatMap(phq9 -> Stream.of(ScreeningLevel.MINIMAL, ScreeningLevel.MILD, ScreeningLevel.MODERATE,
						ScreeningLevel.SEVERE).map(gad7 -> Arguments.of(phq9, gad7)));
	}

	@ParameterizedTest
	@MethodSource("legalPairs")
	void requestsTheMappedRowInOrderAndKeepsTheFirstTwoEligibleExactResources(ScreeningLevel phq9,
			ScreeningLevel gad7) {
		var run = materialize(phq9, gad7, false, resourceId -> ResourceEligibilityOutcome.ELIGIBLE);
		var row = SupportGuideResourceMap.candidates(phq9, gad7);

		assertThat(run.request().requests()).extracting(query -> query.resourceId() + "@" + query.contentVersion()
				+ "/" + query.targetDomain().name())
				.containsExactlyElementsOf(row.stream().map(candidate -> candidate.resourceId() + "@"
						+ candidate.contentVersion() + "/" + candidate.domain()).toList());
		assertThat(run.request().requests()).allSatisfy(query -> {
			assertThat(query.requiredRole()).isEqualTo(RequiredEligibilityRole.PRIMARY);
			var level = "DEPRESSIVE_SYMPTOMS".equals(query.targetDomain().name()) ? phq9 : gad7;
			assertThat(query.screeningLevel().name()).isEqualTo(level.name());
			assertThat(query.supportTier().name()).isEqualTo(pathway(level).name());
		});
		assertThat(run.view().resources()).extracting(resource -> resource.resourceId().toString())
				.containsExactlyElementsOf(row.stream().limit(2).map(Candidate::resourceId).toList());
		assertThat(run.view().resourceResolution().status()).isEqualTo("AVAILABLE");
	}

	@Test
	void skipsAStaleCandidateForTheNextReviewedOneWithoutInventingASubstitute() {
		var run = materialize(ScreeningLevel.MINIMAL, ScreeningLevel.MILD, false,
				resourceId -> resourceId.endsWith("201") ? ResourceEligibilityOutcome.STALE
						: ResourceEligibilityOutcome.ELIGIBLE);

		assertThat(run.view().resources()).extracting(resource -> resource.resourceId().toString())
				.containsExactly("00000000-0000-4000-8000-000000000211", "00000000-0000-4000-8000-000000000202");
		assertThat(run.view().resourceResolution().status()).isEqualTo("AVAILABLE");
	}

	@Test
	void returnsOneResourceAsPartialWhenTheRestOfTheRowIsWithdrawn() {
		var run = materialize(ScreeningLevel.MODERATE, ScreeningLevel.MINIMAL, false,
				resourceId -> resourceId.endsWith("205") ? ResourceEligibilityOutcome.WITHDRAWN
						: ResourceEligibilityOutcome.ELIGIBLE);

		assertThat(run.view().resources()).extracting(resource -> resource.resourceId().toString())
				.containsExactly("00000000-0000-4000-8000-000000000208");
		assertThat(run.view().resourceResolution().status()).isEqualTo("PARTIAL");
	}

	@Test
	void returnsFewerResourcesRatherThanAddingAnIneligibleOne() {
		var run = materialize(ScreeningLevel.MINIMAL, ScreeningLevel.SEVERE, false,
				resourceId -> resourceId.endsWith("206") ? ResourceEligibilityOutcome.ELIGIBLE
						: ResourceEligibilityOutcome.INELIGIBLE);

		assertThat(run.view().resources()).hasSize(1);
		assertThat(run.view().resourceResolution().status()).isEqualTo("AVAILABLE");
	}

	@Test
	void keepsTheProfessionalExplanationFreeOfASelfHelpFirstInstruction() {
		var professional = materialize(ScreeningLevel.SEVERE, ScreeningLevel.SEVERE, false,
				resourceId -> ResourceEligibilityOutcome.ELIGIBLE);
		var selfGuided = materialize(ScreeningLevel.MILD, ScreeningLevel.MILD, false,
				resourceId -> ResourceEligibilityOutcome.ELIGIBLE);

		assertThat(professional.view().explanation().text()).doesNotContain("bắt đầu bằng cách chọn");
		assertThat(selfGuided.view().explanation().text()).contains("bắt đầu bằng cách chọn");
	}

	@Test
	void keepsStoredSafetyFirstWhileStillSavingReviewedResourcesForASevereSafetyPair() {
		var run = materialize(ScreeningLevel.SEVERE, ScreeningLevel.MINIMAL, true,
				resourceId -> ResourceEligibilityOutcome.ELIGIBLE);

		assertThat(run.view().safety().status()).isEqualTo("POSITIVE_SAFETY_SCREEN");
		assertThat(run.view().safety().guidanceCode()).isEqualTo("REVIEW_SAFETY_GUIDANCE");
		assertThat(run.view().resources()).hasSize(2);
	}

	@Test
	void severeBandsAloneDoNotCreateSafetyGuidance() {
		var run = materialize(ScreeningLevel.SEVERE, ScreeningLevel.SEVERE, false,
				resourceId -> ResourceEligibilityOutcome.ELIGIBLE);

		assertThat(run.view().safety().status()).isEqualTo("NEGATIVE_SAFETY_SCREEN");
		assertThat(run.view().safety().guidanceCode()).isEqualTo("STANDARD_SAFETY_REMINDER");
	}

	private Run materialize(ScreeningLevel phq9, ScreeningLevel gad7, boolean positive,
			Function<String, ResourceEligibilityOutcome> outcome) {
		var evaluations = mock(SupportEvaluationV2Service.class);
		var eligibility = mock(ResourceEligibilityClient.class);
		var phrasing = mock(SupportGuidePhrasingClient.class);
		var writer = mock(SupportGuideWriter.class);
		when(evaluations.get(USER_ID, EVALUATION_ID)).thenReturn(evaluation(phq9, gad7, positive));
		stubCollaborators(eligibility, phrasing, writer, outcome, false);
		var service = new SupportGuideService(evaluations, eligibility, phrasing, writer,
				Clock.fixed(NOW, ZoneOffset.UTC));

		var view = service.ensureForEpisode(USER_ID, "user-token", UUID.randomUUID(), UUID.randomUUID(), EVALUATION_ID);
		var request = ArgumentCaptor.forClass(ResourceEligibilityBatchRequest.class);
		verify(eligibility).resolve(request.capture(), eq("user-token"), any());
		return new Run(view, request.getValue());
	}

	private record Run(SupportGuideService.SupportGuideView view, ResourceEligibilityBatchRequest request) { }

	private SupportGuideService.SupportGuideView generate(ResourceEligibilityOutcome outcome, boolean positive) {
		return generate(outcome, positive, false);
	}

	private SupportGuideService.SupportGuideView generate(ResourceEligibilityOutcome outcome, boolean positive,
			boolean aiAvailable) {
		var evaluations = mock(SupportEvaluationV2Service.class);
		var eligibility = mock(ResourceEligibilityClient.class);
		var phrasing = mock(SupportGuidePhrasingClient.class);
		var writer = mock(SupportGuideWriter.class);
		var evaluation = evaluation(positive);
		when(evaluations.evaluate(eq(USER_ID), anyString(), any(), any())).thenReturn(evaluation);
		stubCollaborators(eligibility, phrasing, writer, resourceId -> outcome, aiAvailable);

		var service = new SupportGuideService(evaluations, eligibility, phrasing, writer,
				Clock.fixed(NOW, ZoneOffset.UTC));
		return service.generate(USER_ID, "user-token", "support-guide-unit-0001", UUID.randomUUID(),
				new SupportGuideService.GenerateCommand(PHQ9_ID, GAD7_ID));
	}

	private void stubCollaborators(ResourceEligibilityClient eligibility, SupportGuidePhrasingClient phrasing,
			SupportGuideWriter writer, Function<String, ResourceEligibilityOutcome> outcome, boolean aiAvailable) {
		when(eligibility.resolve(any(), eq("user-token"), any())).thenAnswer(invocation -> {
			var request = (ResourceEligibilityBatchRequest) invocation.getArgument(0);
			var results = request.requests().stream()
					.map(query -> result(query.requestId(), query.resourceId(), outcome.apply(query.resourceId())))
					.toList();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", NOW.toString(), results);
		});
		when(phrasing.phrase(anyString(), eq("user-token"), any())).thenAnswer(invocation ->
				aiAvailable ? new Phrasing("Diễn giải AI đã được kiểm soát.", "STANDARD")
						: new Phrasing(invocation.getArgument(0), "AI_UNAVAILABLE_FALLBACK"));
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
	}

	private EvaluationView evaluation(boolean positive) {
		return evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD, positive);
	}

	private static DomainSupportPathway pathway(ScreeningLevel level) {
		return level == ScreeningLevel.MINIMAL || level == ScreeningLevel.MILD
				? DomainSupportPathway.SELF_GUIDED_SUPPORT : DomainSupportPathway.PROFESSIONAL_SUPPORT_RECOMMENDED;
	}

	private EvaluationView evaluation(ScreeningLevel phq9, ScreeningLevel gad7, boolean positive) {
		var domains = List.of(
				new DomainContributionView(PHQ9_ID, UUID.randomUUID(), "PHQ9", ScreeningDomain.DEPRESSIVE_SYMPTOMS,
						"phq9-v2", "phq9-standard-bands-v1", phq9, pathway(phq9),
						List.of(DomainReasonCode.valueOf("PHQ9_LEVEL_" + phq9.name()))),
				new DomainContributionView(GAD7_ID, UUID.randomUUID(), "GAD7", ScreeningDomain.ANXIETY_SYMPTOMS,
						"gad7-v1", "gad7-standard-bands-v1", gad7, pathway(gad7),
						List.of(DomainReasonCode.valueOf("GAD7_LEVEL_" + gad7.name()))));
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
