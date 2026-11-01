package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.EntitlementSource;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.entitlement.EntitlementClient;
import com.mentalbridge.care.profile.UserProfileEntity;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.reassessment.ReassessmentSummaryService;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.EligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceCategory;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityQuery;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityReasonCode;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityResult;
import com.mentalbridge.care.screeningepisode.ScreeningEpisodeService;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.DomainReasonCode;
import com.mentalbridge.care.support.DomainSupportPathway;
import com.mentalbridge.care.support.SafetyReasonCode;
import com.mentalbridge.care.support.ScreeningDomain;
import com.mentalbridge.care.support.SupportEvaluationV2Service;
import com.mentalbridge.care.support.SupportEvaluationV2Service.DomainContributionView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.EvaluationView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.SafetyEvidenceView;
import com.mentalbridge.care.supportplan.PlanActionEntity.Presentation;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.ActivityMode;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationSnapshot;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PreferredFocus;

class NativeSupportPlanProposalTests {

	private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
	private final UUID userId = UUID.randomUUID();
	private final UUID correlationId = UUID.randomUUID();
	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final SupportPlanTemplateFamilyRepository families = mock(SupportPlanTemplateFamilyRepository.class);
	private final SupportPlanFocusRepository focuses = mock(SupportPlanFocusRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final SupportPlanActivityOccurrenceService activities = mock(SupportPlanActivityOccurrenceService.class);
	private final SupportPlanPersonalizationService preferences = mock(SupportPlanPersonalizationService.class);
	private final EntitlementClient entitlements = mock(EntitlementClient.class);
	private final SupportEvaluationV2Service evaluations = mock(SupportEvaluationV2Service.class);
	private final ScreeningEpisodeService episodes = mock(ScreeningEpisodeService.class);
	private final ResourceEligibilityClient eligibility = mock(ResourceEligibilityClient.class);
	private final SupportPlanV3Service views = mock(SupportPlanV3Service.class);
	private final List<SupportPlanTemplateFamilyEntity> storedFamilies = new ArrayList<>();
	private final List<SupportPlanFocusEntity> storedFocuses = new ArrayList<>();
	private final List<PlanActionEntity> storedActions = new ArrayList<>();
	private NativeSupportPlanWriter writer;
	private NativeSupportPlanService service;
	private SupportPlanEntity storedPlan;
	private EvaluationView evaluation;
	private PersonalizationSnapshot preference;

	@BeforeEach
	void setup() {
		writer = new NativeSupportPlanWriter(plans, families, focuses, actions, profiles, activities,
				mock(SupportPlanScheduleCommandRepository.class), mock(SupportPlanScheduleService.class),
				new ObjectMapper().findAndRegisterModules());
		service = new NativeSupportPlanService(writer, mock(SupportPlanWriter.class), plans, views, entitlements,
				evaluations, episodes, preferences, new SupportPlanPolicy(), eligibility,
				mock(ReassessmentSummaryService.class), Clock.fixed(NOW, ZoneOffset.UTC));
		when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(mock(UserProfileEntity.class)));
		when(profiles.findTimezoneByAccountId(userId)).thenReturn(Optional.of("Asia/Ho_Chi_Minh"));
		when(entitlements.current(eq(userId), anyString(), any())).thenReturn(entitlement(ServicePackage.PLUS));
		when(preferences.currentSnapshot(userId)).thenAnswer(call -> Optional.ofNullable(preference));
		when(evaluations.getCurrentCompatible(eq(userId), any())).thenAnswer(call -> evaluation);
		when(plans.saveAndFlush(any())).thenAnswer(call -> storedPlan = call.getArgument(0));
		when(plans.findByIdAndUserId(any(), eq(userId))).thenAnswer(call -> Optional.ofNullable(storedPlan));
		when(plans.findByIdAndUserIdForUpdate(any(), eq(userId))).thenAnswer(call -> Optional.ofNullable(storedPlan));
		when(plans.findByUserIdAndStatus(eq(userId), eq("DRAFT"))).thenAnswer(call ->
				storedPlan != null && "DRAFT".equals(storedPlan.status()) ? Optional.of(storedPlan) : Optional.empty());
		when(families.save(any())).thenAnswer(call -> {
			SupportPlanTemplateFamilyEntity value = call.getArgument(0);
			storedFamilies.add(value);
			return value;
		});
		when(families.findBySupportPlanIdOrderByOrdinal(any())).thenAnswer(call -> List.copyOf(storedFamilies));
		when(focuses.saveAndFlush(any())).thenAnswer(call -> {
			SupportPlanFocusEntity value = call.getArgument(0);
			storedFocuses.add(value);
			return value;
		});
		when(actions.save(any())).thenAnswer(call -> {
			PlanActionEntity value = call.getArgument(0);
			storedActions.add(value);
			return value;
		});
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(any(), eq(userId)))
				.thenAnswer(call -> List.copyOf(storedActions));
		eligible(query -> true);
		evaluation = evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD, false);
	}

	@Test
	void mildSelfGuidedProposalDefaultsEmotionAndReviewThenRevalidatesOnActivation() {
		propose();

		assertThat(storedPlan.planPolicyVersion()).isEqualTo("support-plan-proposal-v1");
		assertThat(storedFamilies).extracting(SupportPlanTemplateFamilyEntity::family)
				.containsExactly("DEPRESSIVE_SELF_GUIDED", "ANXIETY_SELF_GUIDED");
		assertThat(storedActions).extracting(PlanActionEntity::actionType)
				.containsOnly("RESOURCE", "EMOTION_CHECK_IN", "REASSESSMENT_SCREENING")
				.doesNotContain("JOURNAL", "PROFESSIONAL_SUPPORT", "ROUTINE_SELF_MANAGEMENT", "SELF_REPORT_REVIEW");
		assertThat(ofType("RESOURCE")).hasSize(4).allSatisfy(action -> {
			assertThat(action.sourceOwner()).isEqualTo("CONTENT");
			assertThat(action.sourceRevision()).isEqualTo("0");
			assertThat(action.sourcePublicationId()).isNotNull();
		});
		assertThat(ofType("EMOTION_CHECK_IN")).singleElement().satisfies(action -> {
			assertThat(action.provenanceType()).isEqualTo("SYSTEM_POLICY");
			assertThat(action.provenanceReferenceId()).isEqualTo(NativeSupportPlanWriter.PROPOSAL_POLICY);
			assertThat(action.provenanceReferenceVersion()).isEqualTo(NativeSupportPlanWriter.PROPOSAL_POLICY);
			assertThat(action.rationale()).doesNotContain("Bạn đã chọn");
			assertThat(action.presentationSummary()).isEqualTo("Ghi nhận cảm xúc của bạn mỗi ngày; không phải đánh giá chẩn đoán.");
		});
		assertThat(ofType("REASSESSMENT_SCREENING")).singleElement().satisfies(action -> {
			assertThat(action.sourceOwner()).isEqualTo("CARE");
			assertThat(action.sourceKind()).isEqualTo("QUESTIONNAIRE_DEFINITION");
		});

		activate();

		assertThat(storedPlan.status()).isEqualTo("ACTIVE");
		verify(activities).activateUserDraft(storedPlan, NOW);
		verify(entitlements, times(2)).current(eq(userId), anyString(), any());
	}

	@Test
	void reassessmentUsesPhq9DefinitionWhenAnxietyContributionComesFirst() {
		evaluation = new EvaluationView(evaluation.supportEvaluationId(), evaluation.evaluationVersion(), evaluation.policyVersion(),
				evaluation.evaluatedAt(), evaluation.contributingDomains().reversed(), evaluation.safetyEvidence(),
				evaluation.disclaimerCode(), evaluation.disclaimer());
		var phq9 = evaluation.contributingDomains().stream().filter(domain -> "PHQ9".equals(domain.instrument()))
				.findFirst().orElseThrow();

		propose();

		assertThat(ofType("REASSESSMENT_SCREENING")).singleElement().satisfies(action ->
				assertThat(action.sourceId()).isEqualTo(phq9.questionnaireDefinitionId().toString()));
		activate();
		assertThat(storedPlan.status()).isEqualTo("ACTIVE");
	}

	@Test
	void cycleUsesProfileTimezoneBeforeTheFirstDraftPersistence() {
		when(profiles.findTimezoneByAccountId(userId)).thenReturn(Optional.of("Asia/Tokyo"));
		doAnswer(call -> {
			SupportPlanEntity plan = call.getArgument(0);
			assertThat(plan.cycleTimezone()).isEqualTo("Asia/Tokyo");
			assertThat(plan.cycleNumber()).isEqualTo(1);
			assertThat(plan.cyclePausedDays()).isZero();
			assertThat(plan.cycleStartedOn()).isNull();
			storedPlan = plan;
			return plan;
		}).when(plans).saveAndFlush(any());

		propose();

		assertThat(storedPlan.version()).isZero();
	}

	@Test
	void skippedPreferencesKeepSafeEmotionDefaultAndDoNotInferJournal() {
		preference = preference(null, List.of());
		bindPreference();

		propose();

		assertThat(preference.provided()).isFalse();
		assertThat(ofType("EMOTION_CHECK_IN")).hasSize(1);
		assertThat(ofType("JOURNAL")).isEmpty();
		activate();
		assertThat(storedPlan.status()).isEqualTo("ACTIVE");
	}

	@Test
	void providedPreferencesWithoutEmotionOptOutWhileExplicitJournalIsAdmitted() {
		preference = preference(PreferredFocus.MOOD, List.of(ActivityMode.JOURNALING));
		bindPreference();

		propose();

		assertThat(ofType("EMOTION_CHECK_IN")).isEmpty();
		assertThat(ofType("JOURNAL")).singleElement().satisfies(action -> {
			assertThat(action.sourceOwner()).isEqualTo("JOURNAL_AI");
			assertThat(action.sourceKind()).isEqualTo("JOURNAL_TEMPLATE");
			assertThat(action.sourceId()).isEqualTo(NativeSupportPlanWriter.JOURNAL_INTENT);
			assertThat(action.provenanceType()).isEqualTo("USER_SELECTED");
			assertThat(action.provenanceReferenceId()).isEqualTo(preference.personalizationId().toString());
		});
		activate();
		assertThat(storedPlan.status()).isEqualTo("ACTIVE");
	}

	@Test
	void explicitEmotionPreferenceKeepsExactUserPreferenceProvenance() {
		preference = preference(null, List.of(ActivityMode.EMOTION_CHECK_IN));
		bindPreference();
		propose();

		assertThat(ofType("EMOTION_CHECK_IN")).singleElement().satisfies(action -> {
			assertThat(action.provenanceType()).isEqualTo("USER_SELECTED");
			assertThat(action.provenanceReferenceId()).isEqualTo(preference.personalizationId().toString());
			assertThat(action.provenanceReferenceVersion()).isEqualTo("1");
			assertThat(action.presentationSummary()).isEqualTo("Ghi nhận cảm xúc của bạn theo nhịp đã chọn; không phải đánh giá chẩn đoán.");
		});
		assertThat(ofType("JOURNAL")).isEmpty();
		activate();
	}

	@Test
	void moderateProfessionalFamiliesKeepProfessionalActionFirstAndActivateAllAdmittedDomains() {
		evaluation = evaluation(ScreeningLevel.MODERATE, ScreeningLevel.MODERATE, false);
		preference = preference(null, List.of(ActivityMode.JOURNALING, ActivityMode.EMOTION_CHECK_IN));
		bindPreference();
		propose();

		assertThat(storedFamilies).allSatisfy(family -> assertThat(family.family()).endsWith("_PROFESSIONAL_ADJUNCT"));
		assertThat(ofType("PROFESSIONAL_SUPPORT")).hasSize(2).allSatisfy(professional -> {
			assertThat(professional.sourceOwner()).isEqualTo("CONSULTATION");
			assertThat(professional.sourceKind()).isEqualTo("PROFESSIONAL_SUPPORT_PATHWAY");
			assertThat(ofType("RESOURCE").stream().filter(resource -> resource.planFocusId().equals(professional.planFocusId())))
					.allSatisfy(resource -> assertThat(resource.ordinal()).isGreaterThan(professional.ordinal()));
		});
		assertThat(ofType("REASSESSMENT_SCREENING")).hasSize(1);
		activate();
		assertThat(storedPlan.status()).isEqualTo("ACTIVE");
	}

	@Test
	void safetyPositiveContextFailsClosedWithoutDraftOrResourceLookup() {
		evaluation = evaluation(ScreeningLevel.MODERATE, ScreeningLevel.MODERATE, true);

		assertThatThrownBy(this::propose).isInstanceOfSatisfying(ApiException.class,
				exception -> {
					assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_SAFETY_REVIEW_REQUIRED");
				});

		assertThat(storedPlan).isNull();
		assertThat(storedActions).isEmpty();
		verify(plans, never()).saveAndFlush(any());
		verifyNoInteractions(eligibility);
	}

	@Test
	void activationRechecksSafetyAndLeavesTheDraftUnchanged() {
		propose();
		var safetyPositive = evaluation(ScreeningLevel.MILD, ScreeningLevel.MILD, true);
		evaluation = new EvaluationView(evaluation.supportEvaluationId(), evaluation.evaluationVersion(), evaluation.policyVersion(),
				evaluation.evaluatedAt(), evaluation.contributingDomains(), safetyPositive.safetyEvidence(),
				evaluation.disclaimerCode(), evaluation.disclaimer());

		assertThatThrownBy(this::activate).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_SAFETY_REVIEW_REQUIRED"));

		assertThat(storedPlan.status()).isEqualTo("DRAFT");
		assertThat(storedActions).allSatisfy(action -> assertThat(action.status()).isEqualTo("DRAFT"));
		verify(activities, never()).activateUserDraft(any(), any());
	}

	@Test
	void sparseCoreResourceFailsClosedWithoutPersistingDraft() {
		eligible(query -> !query.resourceId().endsWith("205"));

		assertThatThrownBy(this::propose).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_CORE_UNAVAILABLE"));

		assertThat(storedPlan).isNull();
		verify(plans, never()).saveAndFlush(any());
	}

	@Test
	void sparseOptionalResourcesAreDroppedAndRemainingProposalActivates() {
		evaluation = evaluation(ScreeningLevel.MODERATE, ScreeningLevel.MILD, false);
		var request = new SupportPlanPolicy().request(evaluation);
		var optionalRequests = request.slots().stream().filter(slot -> "OPTIONAL".equals(slot.kind()))
				.flatMap(slot -> slot.resourceIds().stream()).toList();
		eligible(query -> query.requiredRole().name().equals("PRIMARY") || !optionalRequests.contains(query.resourceId()));

		propose();

		assertThat(ofType("RESOURCE")).hasSize(2);
		assertThat(ofType("PROFESSIONAL_SUPPORT")).hasSize(1);
		activate();
		assertThat(storedPlan.status()).isEqualTo("ACTIVE");
	}

	@Test
	void activationRevalidatesHistoricalPreferenceSnapshot() {
		preference = preference(null, List.of(ActivityMode.JOURNALING, ActivityMode.EMOTION_CHECK_IN));
		bindPreference();
		propose();
		var original = preference;
		preference = preference(PreferredFocus.SLEEP, List.of(ActivityMode.READING));
		when(preferences.snapshot(original.personalizationId(), userId, original.revision())).thenReturn(Optional.of(original));

		activate();

		verify(preferences).snapshot(original.personalizationId(), userId, original.revision());
		assertThat(storedPlan.personalizationId()).isEqualTo(original.personalizationId());
		assertThat(storedPlan.personalizationRevision()).isEqualTo(original.revision());
	}

	@Test
	void oldJournalPolicyDoesNotAcquireDefaultEmotionOrReviewActionsDuringActivation() {
		propose();
		storedActions.removeIf(action -> !"RESOURCE".equals(action.actionType()));
		useHistoricalPolicy(NativeSupportPlanWriter.POLICY);

		activate();

		assertThat(storedPlan.status()).isEqualTo("ACTIVE");
		assertThat(storedActions).allSatisfy(action -> assertThat(action.actionType()).isEqualTo("RESOURCE"));
	}

	@Test
	void oldEmotionPolicyRetainsExplicitEmotionButDoesNotAdmitJournal() {
		preference = preference(null, List.of(ActivityMode.EMOTION_CHECK_IN));
		bindPreference();
		propose();
		storedActions.removeIf(action -> "REASSESSMENT_SCREENING".equals(action.actionType()));
		useHistoricalPolicy(NativeSupportPlanWriter.EMOTION_POLICY);

		activate();

		assertThat(storedPlan.status()).isEqualTo("ACTIVE");
		assertThat(ofType("EMOTION_CHECK_IN")).hasSize(1);
		assertThat(ofType("JOURNAL")).isEmpty();
	}

	@Test
	void activationRejectsMissingDefaultEmotionAndLeavesDraftUnchanged() {
		propose();
		storedActions.removeIf(action -> "EMOTION_CHECK_IN".equals(action.actionType()));

		assertPolicyStaleOnActivation();
	}

	@Test
	void activationRejectsMissingReviewActionAndLeavesDraftUnchanged() {
		propose();
		storedActions.removeIf(action -> "REASSESSMENT_SCREENING".equals(action.actionType()));

		assertPolicyStaleOnActivation();
	}

	@Test
	void activationRejectsProfessionalSupportWithAnUnadmittedPathway() {
		evaluation = evaluation(ScreeningLevel.MODERATE, ScreeningLevel.MILD, false);
		propose();
		var original = ofType("PROFESSIONAL_SUPPORT").getFirst();
		storedActions.set(storedActions.indexOf(original), new PlanActionEntity(original.id(), storedPlan.id(),
				original.planFocusId(), userId, original.ordinal(), "PROFESSIONAL_SUPPORT", original.purposeCode(), null,
				new SourceReference("CONSULTATION", "PROFESSIONAL_SUPPORT_PATHWAY", "unadmitted-pathway", "1",
						NativeSupportPlanWriter.PROPOSAL_POLICY, null, null, NOW),
				new Provenance("SYSTEM_POLICY", original.provenanceReferenceId(), NativeSupportPlanWriter.PROPOSAL_POLICY, NOW),
				new Presentation("Synthetic professional support", null, null, null), NOW));

		assertPolicyStaleOnActivation();
	}

	@Test
	void activationRejectsProfessionalSupportRankedAfterItsAdjunctResource() {
		evaluation = evaluation(ScreeningLevel.MODERATE, ScreeningLevel.MILD, false);
		propose();
		var original = ofType("PROFESSIONAL_SUPPORT").getFirst();
		storedActions.set(storedActions.indexOf(original), new PlanActionEntity(original.id(), storedPlan.id(),
				original.planFocusId(), userId, storedActions.size() + 1, "PROFESSIONAL_SUPPORT", original.purposeCode(), null,
				new SourceReference("CONSULTATION", "PROFESSIONAL_SUPPORT_PATHWAY", "specialist-discovery", "1",
						NativeSupportPlanWriter.PROPOSAL_POLICY, null, null, NOW),
				new Provenance("SYSTEM_POLICY", original.provenanceReferenceId(), NativeSupportPlanWriter.PROPOSAL_POLICY, NOW),
				new Presentation("Synthetic professional support", null, null, null), NOW));

		assertPolicyStaleOnActivation();
	}

	@Test
	void activationRejectsJournalNotAdmittedBySnapshottedPreferences() {
		preference = preference(null, List.of(ActivityMode.READING));
		bindPreference();
		propose();
		storedActions.add(new PlanActionEntity(UUID.randomUUID(), storedPlan.id(), storedActions.getFirst().planFocusId(),
				userId, storedActions.size() + 1, "JOURNAL", "WRITE_SHORT_REFLECTION", null,
				new SourceReference("JOURNAL_AI", "JOURNAL_TEMPLATE", NativeSupportPlanWriter.JOURNAL_INTENT,
						"1", NativeSupportPlanWriter.JOURNAL_INTENT_POLICY, null, null, NOW),
				new Provenance("USER_SELECTED", preference.personalizationId().toString(), "1", NOW),
				new Presentation("Synthetic reflection", "Synthetic summary", null, null), NOW));

		assertPolicyStaleOnActivation();
	}

	@Test
	void proposalReplayReturnsStoredDraftWithoutCallingCurrentProviders() throws Exception {
		UUID planId = UUID.randomUUID();
		var request = mock(SupportPlanRepository.RequestRow.class);
		when(request.getPlanId()).thenReturn(planId);
		when(request.getRequestHash()).thenReturn(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(("NATIVE_PROPOSE\n" + evaluation.supportEvaluationId()).getBytes(StandardCharsets.UTF_8))));
		when(plans.findRequest(userId, "proposal-replay")).thenReturn(Optional.of(request));
		var replayWriter = mock(NativeSupportPlanWriter.class);
		var replayService = new NativeSupportPlanService(replayWriter, mock(SupportPlanWriter.class), plans, views,
				entitlements, evaluations, episodes, preferences, new SupportPlanPolicy(), eligibility,
				mock(ReassessmentSummaryService.class), Clock.fixed(NOW, ZoneOffset.UTC));

		replayService.propose(userId, evaluation.supportEvaluationId(), "bearer", "proposal-replay", correlationId);

		verify(replayWriter).required(userId, planId);
		verify(views).get(userId, planId);
		verifyNoInteractions(entitlements, evaluations, episodes, preferences, eligibility);
	}

	@Test
	void activationReplayDoesNotRevalidateChangedEntitlementOrSources() {
		var replayWriter = mock(NativeSupportPlanWriter.class);
		UUID planId = UUID.randomUUID();
		when(replayWriter.replay(eq(userId), eq("activate-replay"), anyString(), eq("ACTIVATE"))).thenReturn(planId);
		var replayService = new NativeSupportPlanService(replayWriter, mock(SupportPlanWriter.class), plans, views,
				entitlements, evaluations, episodes, preferences, new SupportPlanPolicy(), eligibility,
				mock(ReassessmentSummaryService.class), Clock.fixed(NOW, ZoneOffset.UTC));

		replayService.activate(userId, planId, 0, "bearer", "activate-replay", correlationId);

		verify(views).get(userId, planId);
		verifyNoInteractions(entitlements, evaluations, episodes, preferences, eligibility);
	}

	@Test
	void reviewedFocusOrderSurvivesActivationAndLaterAdmission() {
		var policy = new SupportPlanPolicy();
		var request = policy.request(evaluation);
		var proposal = policy.compose(request, eligibility.resolve(request.batch(), "bearer", correlationId));
		var reversed = new ArrayList<>(proposal.families());
		java.util.Collections.reverse(reversed);
		writer.propose(userId, "reviewed-focus", "reviewed-hash", evaluation, entitlement(ServicePackage.PLUS),
				new SupportPlanPolicy.Proposal(List.copyOf(reversed), proposal.slots(), proposal.resourcePolicyVersion(),
						proposal.resourcesResolvedAt()), null, "REMINDER", "Reminder", NOW);

		activate();
		service.revalidateCurrent(userId, storedPlan.id(), "bearer", correlationId);

		assertThat(storedPlan.status()).isEqualTo("ACTIVE");
		assertThat(storedFamilies.stream().map(SupportPlanTemplateFamilyEntity::targetDomain).toList())
				.containsExactly("DEPRESSIVE_SYMPTOMS", "ANXIETY_SYMPTOMS");
		assertThat(storedFocuses).extracting(SupportPlanFocusEntity::ordinal).containsExactly((short) 1, (short) 2);
		assertThat(storedActions.stream().filter(action -> "RESOURCE".equals(action.actionType())
				&& storedFocuses.getFirst().id().equals(action.planFocusId())).map(PlanActionEntity::purposeCode))
				.containsExactly("ANXIETY_PSYCHOEDUCATION", "ANXIETY_SUPPORT_ACTIVITY");
	}

	@Test
	void freeEntitlementCannotCreateProposal() {
		when(entitlements.current(eq(userId), anyString(), any())).thenReturn(entitlement(ServicePackage.FREE));

		assertThatThrownBy(this::propose).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_ENTITLEMENT_REQUIRED");
		});
		assertThat(storedPlan).isNull();
		verifyNoInteractions(eligibility);
	}

	@Test
	void premiumEntitlementCanProposeAndActivate() {
		when(entitlements.current(eq(userId), anyString(), any())).thenReturn(entitlement(ServicePackage.PREMIUM));

		propose();
		activate();

		assertThat(storedPlan.entitlementPackage()).isEqualTo("PREMIUM");
		assertThat(storedPlan.status()).isEqualTo("ACTIVE");
	}

	@Test
	void activationRechecksExactResourceEligibilityAndLeavesTheDraftUnchanged() {
		propose();
		eligible(query -> false);

		assertThatThrownBy(this::activate).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("RESOURCE_VERSION_STALE"));

		assertThat(storedPlan.status()).isEqualTo("DRAFT");
		verify(activities, never()).activateUserDraft(any(), any());
	}

	@Test
	void entitlementDowngradeBeforeActivationLeavesDraftAndActionsUnchanged() {
		propose();
		when(entitlements.current(eq(userId), anyString(), any())).thenReturn(entitlement(ServicePackage.FREE));

		assertThatThrownBy(this::activate).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_ENTITLEMENT_REQUIRED"));

		assertThat(storedPlan.status()).isEqualTo("DRAFT");
		assertThat(storedActions).allSatisfy(action -> assertThat(action.status()).isEqualTo("DRAFT"));
		verify(activities, never()).activateUserDraft(any(), any());
	}

	@Test
	void secondDistinctProposalReturnsDraftConflictWithoutOverwritingOriginal() {
		propose();
		UUID original = storedPlan.id();
		int count = storedActions.size();

		assertThatThrownBy(() -> service.propose(userId, evaluation.supportEvaluationId(), "bearer",
				"another-proposal", correlationId)).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
			assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_DRAFT_EXISTS");
		});

		assertThat(storedPlan.id()).isEqualTo(original);
		assertThat(storedActions).hasSize(count);
		verify(plans, times(1)).saveAndFlush(any());
	}

	private void propose() {
		service.propose(userId, evaluation.supportEvaluationId(), "bearer", "new-proposal", correlationId);
	}

	private void activate() {
		service.activate(userId, storedPlan.id(), storedPlan.version(), "bearer", "activate-proposal", correlationId);
	}

	private List<PlanActionEntity> ofType(String type) {
		return storedActions.stream().filter(action -> type.equals(action.actionType())).toList();
	}

	private void assertPolicyStaleOnActivation() {
		assertThatThrownBy(this::activate).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_POLICY_STALE"));
		assertThat(storedPlan.status()).isEqualTo("DRAFT");
		verify(activities, never()).activateUserDraft(any(), any());
	}

	private void bindPreference() {
		when(preferences.snapshot(preference.personalizationId(), userId, preference.revision()))
				.thenReturn(Optional.of(preference));
	}

	private void useHistoricalPolicy(String policy) {
		var original = storedPlan;
		storedPlan = SupportPlanEntity.nativeDraft(original.id(), userId, evaluation.supportEvaluationId(),
				evaluation.policyVersion(), evaluation.evaluatedAt(), policy, "PLUS", "DEMO", "entitlement-v1", 1,
				NOW, original.rationaleCode(), original.rationaleText(), original.safetyStatus(), original.safetyReasonCode(),
				original.safetyPolicyVersion(), original.safetyGuidanceCode(), original.safetyGuidance(), storedActions.size(), NOW);
		storedPlan.snapshotPersonalization(preference);
	}

	private PersonalizationSnapshot preference(PreferredFocus focus, List<ActivityMode> modes) {
		return new PersonalizationSnapshot(UUID.randomUUID(), userId, SupportPlanPersonalizationService.SCHEMA_VERSION,
				1, focus, null, null, null, modes, List.of(), NOW);
	}

	private CurrentEntitlementResponse entitlement(ServicePackage servicePackage) {
		return new CurrentEntitlementResponse(userId, servicePackage, EntitlementSource.DEMO, "synthetic",
				NOW.minusSeconds(86400), null, "entitlement-v1", 1, NOW);
	}

	private EvaluationView evaluation(ScreeningLevel depression, ScreeningLevel anxiety, boolean safetyPositive) {
		return new EvaluationView(UUID.randomUUID(), 2, "mb-support-routing-capstone-v2", NOW,
				List.of(domain("PHQ9", ScreeningDomain.DEPRESSIVE_SYMPTOMS, depression),
						domain("GAD7", ScreeningDomain.ANXIETY_SYMPTOMS, anxiety)),
				new SafetyEvidenceView(UUID.randomUUID(), "PHQ9", "PHQ9_ITEM_9",
						safetyPositive ? SafetyStatus.POSITIVE_SAFETY_SCREEN : SafetyStatus.NEGATIVE_SAFETY_SCREEN,
						"MB-SAFETY-PHQ9-001-v1", safetyPositive ? SafetyReasonCode.PHQ9_ITEM9_POSITIVE : SafetyReasonCode.PHQ9_ITEM9_NEGATIVE),
				"SCREENING_NOT_DIAGNOSIS", "Synthetic disclaimer");
	}

	private DomainContributionView domain(String instrument, ScreeningDomain domain, ScreeningLevel level) {
		return new DomainContributionView(UUID.randomUUID(), UUID.randomUUID(), instrument, domain, "questionnaire-v1",
				instrument.toLowerCase() + "-scoring-v1", level,
				level == ScreeningLevel.MINIMAL || level == ScreeningLevel.MILD ? DomainSupportPathway.SELF_GUIDED_SUPPORT
						: DomainSupportPathway.PROFESSIONAL_SUPPORT_RECOMMENDED,
				List.of(DomainReasonCode.valueOf(instrument + "_LEVEL_" + level.name())));
	}

	private void eligible(Predicate<ResourceEligibilityQuery> admitted) {
		doAnswer(call -> {
			ResourceEligibilityBatchRequest request = call.getArgument(0);
			var results = request.requests().stream().map(query -> {
				boolean available = admitted.test(query);
				return new ResourceEligibilityResult(query.requestId(), query.resourceId(), query.contentVersion(),
						available ? ResourceEligibilityOutcome.ELIGIBLE : ResourceEligibilityOutcome.INELIGIBLE,
						available ? ResourceEligibilityReasonCode.ELIGIBLE_MATCH : ResourceEligibilityReasonCode.DOMAIN_OR_PATHWAY_NOT_ELIGIBLE,
						available ? "PRIMARY".equals(query.requiredRole().name()) ? EligibilityRole.PRIMARY : EligibilityRole.ADJUNCT : null,
						available ? UUID.nameUUIDFromBytes(query.requestId().getBytes(StandardCharsets.UTF_8)).toString() : null,
						available ? ResourceCategory.ARTICLE : null, available ? "Synthetic reviewed resource" : null,
						available ? "Synthetic general wellbeing content" : null, null);
			}).toList();
			return new ResourceEligibilityBatchResponse("content-eligibility-v1", NOW.toString(), results);
		}).when(eligibility).resolve(any(), anyString(), any());
	}
}
