package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.EntitlementSource;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.entitlement.EntitlementClient;
import com.mentalbridge.care.reassessment.ReassessmentSummaryService;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;
import com.mentalbridge.care.screeningepisode.ScreeningEpisodeService;
import com.mentalbridge.care.support.SafetyReasonCode;
import com.mentalbridge.care.support.SupportEvaluationService;
import com.mentalbridge.care.support.SupportEvaluationV2Service;
import com.mentalbridge.care.support.SupportEvaluationV2Service.EvaluationView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.SafetyEvidenceView;
import com.mentalbridge.care.supportplan.PlanActionEntity.Presentation;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.Proposal;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.ProposalRequest;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.ResourceDraft;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SlotDraft;
import com.mentalbridge.care.supportplan.SupportPlanWriter.StoredPlan;

class SupportPlanSystemCopyTests {

	private static final Instant NOW = Instant.parse("2026-10-13T08:00:00Z");
	private static final String RATIONALE = "MentalBridge đã ghép các lựa chọn hỗ trợ sức khỏe tổng quát theo từng miền sàng lọc, chính sách Care và phiên bản tài nguyên đã được duyệt. Đây không phải chẩn đoán hay kế hoạch điều trị.";
	private static final String STANDARD_SAFETY = "Nếu tình trạng của bạn thay đổi hoặc bạn cảm thấy không an toàn, hãy chủ động tìm hỗ trợ trực tiếp phù hợp tại khu vực bạn đã chọn.";
	private static final String DISCLAIMER = "SupportPlan này hỗ trợ sức khỏe tổng quát và tự quản lý; không phải chẩn đoán, đơn thuốc hoặc kế hoạch điều trị, và không thay thế hỗ trợ an toàn hay hỗ trợ chuyên môn.";
	private static final String LEGACY_RATIONALE = "MentalBridge Ä‘Ã£ ghÃ©p cÃ¡c lá»±a chá»n há»— trá»£ sá»©c khá»e tá»•ng quÃ¡t theo tá»«ng miá»n sÃ ng lá»c, chÃ­nh sÃ¡ch Care vÃ  phiÃªn báº£n tÃ i nguyÃªn Ä‘Ã£ Ä‘Æ°á»£c duyá»‡t. ÄÃ¢y khÃ´ng pháº£i cháº©n Ä‘oÃ¡n hay káº¿ hoáº¡ch Ä‘iá»u trá»‹.";
	private static final String LEGACY_STANDARD_SAFETY = "Náº¿u tÃ¬nh tráº¡ng cá»§a báº¡n thay Ä‘á»•i hoáº·c báº¡n cáº£m tháº¥y khÃ´ng an toÃ n, hÃ£y chá»§ Ä‘á»™ng tÃ¬m há»— trá»£ trá»±c tiáº¿p phÃ¹ há»£p táº¡i khu vá»±c báº¡n Ä‘Ã£ chá»n.";
	private final UUID userId = UUID.randomUUID();
	private final UUID planId = UUID.randomUUID();
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final EntitlementClient entitlements = mock(EntitlementClient.class);
	private final SupportEvaluationV2Service evaluations = mock(SupportEvaluationV2Service.class);
	private final ResourceEligibilityClient eligibility = mock(ResourceEligibilityClient.class);
	private final SupportPlanPolicy policy = mock(SupportPlanPolicy.class);
	private final SupportPlanWriter writer = mock(SupportPlanWriter.class);
	private final SupportPlanPersonalizationService personalizations = mock(SupportPlanPersonalizationService.class);
	private final ReassessmentSummaryService reassessments = mock(ReassessmentSummaryService.class);
	private final ScreeningEpisodeService screeningEpisodes = mock(ScreeningEpisodeService.class);
	private final SupportPlanService service = new SupportPlanService(entitlements, evaluations, eligibility,
			policy, writer, personalizations, reassessments, screeningEpisodes, clock);
	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final SupportPlanTemplateFamilyRepository families = mock(SupportPlanTemplateFamilyRepository.class);
	private final SupportPlanSlotRepository slots = mock(SupportPlanSlotRepository.class);
	private final SupportPlanFocusRepository focuses = mock(SupportPlanFocusRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final SupportPlanActivityScheduleRepository schedules = mock(SupportPlanActivityScheduleRepository.class);
	private final SupportPlanActivityOccurrenceService activities = mock(SupportPlanActivityOccurrenceService.class);
	private final SupportPlanV3Service v3 = new SupportPlanV3Service(plans, families, slots, focuses, actions,
			schedules, activities, clock);

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void newProposalsPersistReadableCopyAndKeepPositiveSafetyGuidance(boolean safetyPositive) {
		UUID evaluationId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		var status = safetyPositive ? SafetyStatus.POSITIVE_SAFETY_SCREEN : SafetyStatus.NEGATIVE_SAFETY_SCREEN;
		var evaluation = new EvaluationView(evaluationId, 2, "mb-support-routing-capstone-v2", NOW,
				List.of(), new SafetyEvidenceView(UUID.randomUUID(), "PHQ_9", "PHQ9_ITEM9", status,
						"safety-v1", safetyPositive ? SafetyReasonCode.PHQ9_ITEM9_POSITIVE : SafetyReasonCode.PHQ9_ITEM9_NEGATIVE),
				"SCREENING_NOT_DIAGNOSIS", "Không phải chẩn đoán.");
		var entitlement = new CurrentEntitlementResponse(userId, ServicePackage.PLUS, EntitlementSource.DEMO,
				"synthetic-demo", NOW, NOW.plusSeconds(86400), "service-entitlement-v1", 1, NOW);
		var batch = new ResourceEligibilityBatchRequest(List.of());
		var request = new ProposalRequest(List.of(), List.of(), batch);
		var resolved = mock(ResourceEligibilityBatchResponse.class);
		var proposal = new Proposal(List.of(), List.of(), "content-eligibility-v1", NOW);
		String guidanceCode = safetyPositive ? "REVIEW_SAFETY_GUIDANCE" : "STANDARD_SAFETY_REMINDER";
		String guidance = safetyPositive ? SupportEvaluationService.SAFETY_FALLBACK : STANDARD_SAFETY;
		when(personalizations.currentSnapshot(userId)).thenReturn(Optional.empty());
		when(entitlements.current(userId, "bearer", correlationId)).thenReturn(entitlement);
		when(evaluations.getCurrentCompatible(userId, evaluationId)).thenReturn(evaluation);
		when(policy.request(evaluation, null)).thenReturn(request);
		when(eligibility.resolve(batch, "bearer", correlationId)).thenReturn(resolved);
		when(policy.compose(request, resolved, null)).thenReturn(proposal);
		when(writer.persist(eq(userId), eq("copy-request"), anyString(), eq(evaluation), eq(entitlement),
				eq(proposal), isNull(), anyString(), anyString(), anyString(), eq(NOW)))
				.thenAnswer(invocation -> stored(legacyPlan(invocation.getArgument(7), status.name(),
						invocation.getArgument(8), invocation.getArgument(9))));

		var view = service.propose(userId, "bearer", "copy-request", correlationId,
				new SupportPlanService.ProposeCommand(evaluationId));

		verify(writer).persist(eq(userId), eq("copy-request"), anyString(), eq(evaluation), eq(entitlement),
				eq(proposal), isNull(), eq(RATIONALE), eq(guidanceCode), eq(guidance), eq(NOW));
		assertThat(view.rationale().text()).isEqualTo(RATIONALE);
		assertThat(view.safety().status()).isEqualTo(status.name());
		assertThat(view.safety().guidanceCode()).isEqualTo(guidanceCode);
		assertThat(view.safety().guidance()).isEqualTo(guidance);
		assertThat(view.disclaimer()).isEqualTo(DISCLAIMER);
	}

	@ParameterizedTest
	@ValueSource(strings = {"DRAFT", "ACTIVE", "COMPLETED"})
	void v2ReadsCorrectOnlyDisplayEncodingAndNeverRewriteSnapshots(String status) {
		var plan = legacyPlan(LEGACY_RATIONALE, "NEGATIVE_SAFETY_SCREEN", "STANDARD_SAFETY_REMINDER",
				LEGACY_STANDARD_SAFETY);
		if (!"DRAFT".equals(status)) plan.activate(NOW.plusSeconds(1));
		if ("COMPLETED".equals(status)) plan.complete("USER_DECISION", NOW.plusSeconds(2));
		var stored = stored(plan);
		when(writer.required(userId, planId)).thenReturn(stored);
		when(writer.history(userId, null, null, 11)).thenReturn(List.of(stored));

		var views = new ArrayList<SupportPlanService.SupportPlanView>();
		views.add(service.get(userId, planId));
		if ("DRAFT".equals(status)) {
			when(writer.currentDraft(userId)).thenReturn(stored);
			views.add(service.currentDraft(userId));
		}
		if ("ACTIVE".equals(status)) {
			when(writer.currentPlan(userId)).thenReturn(stored);
			views.add(service.currentPlan(userId));
		}
		if ("COMPLETED".equals(status)) views.add(service.history(userId, 10, null).items().getFirst());
		for (var view : views) {
			assertThat(view.rationale().text()).isEqualTo(RATIONALE);
			assertThat(view.safety().guidance()).isEqualTo(STANDARD_SAFETY);
			assertThat(view.safety().status()).isEqualTo("NEGATIVE_SAFETY_SCREEN");
			assertThat(view.safety().policyVersion()).isEqualTo("safety-v1");
			assertThat(view.supportPlanId()).isEqualTo(planId);
			assertThat(view.status()).isEqualTo(status);
			assertThat(view.version()).isEqualTo(plan.version());
			assertThat(view.updatedAt()).isEqualTo(plan.updatedAt());
			assertThat(view.completedAt()).isEqualTo(plan.completedAt());
			assertThat(view.source().selectionPolicyVersion()).isEqualTo(plan.selectionPolicyVersion());
			assertThat(view.disclaimer()).isEqualTo(DISCLAIMER);
		}
		assertThat(plan.rationaleText()).isEqualTo(LEGACY_RATIONALE);
		assertThat(plan.safetyGuidance()).isEqualTo(LEGACY_STANDARD_SAFETY);
		assertThat(plan.version()).isZero();
		verifyNoInteractions(entitlements, evaluations, eligibility, policy, personalizations, reassessments, screeningEpisodes);
	}

	@Test
	void v3AdapterCorrectsLegacyFocusAndActionCopyWithoutTouchingStoredContent() {
		var plan = legacyPlan(LEGACY_RATIONALE, "NEGATIVE_SAFETY_SCREEN", "STANDARD_SAFETY_REMINDER",
				LEGACY_STANDARD_SAFETY);
		var resource = new ResourceDraft(UUID.randomUUID(), 3, UUID.randomUUID(), "ADJUNCT", "ARTICLE",
				"Nội dung đã duyệt", "Tóm tắt đã duyệt", null);
		var slot = new SupportPlanSlotEntity(UUID.randomUUID(), planId, 1,
				new SlotDraft("mood-adjunct", "OPTIONAL", "DEPRESSIVE_SYMPTOMS", "SUPPORT_MOOD", resource, List.of()));
		when(plans.findByIdAndUserId(planId, userId)).thenReturn(Optional.of(plan));
		when(families.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of(
				new SupportPlanTemplateFamilyEntity(UUID.randomUUID(), planId, 1,
						"DEPRESSIVE_PROFESSIONAL_ADJUNCT", "DEPRESSIVE_SYMPTOMS")));
		when(slots.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of(slot));

		var view = v3.get(userId, planId);

		assertThat(view.representationMode()).isEqualTo("V2_RESOURCE_ADAPTER");
		assertThat(view.focuses()).singleElement().satisfies(focus -> {
			assertThat(focus.rationale()).isEqualTo(RATIONALE);
			assertThat(focus.actions()).hasSize(2).allSatisfy(action -> assertThat(action.rationale()).isEqualTo(RATIONALE));
			assertThat(focus.actions().get(1).source().sourceRevision()).isEqualTo("3");
		});
		assertThat(view.safety().guidance()).isEqualTo(STANDARD_SAFETY);
		assertThat(view.disclaimer()).isEqualTo(DISCLAIMER);
		assertThat(plan.rationaleText()).isEqualTo(LEGACY_RATIONALE);
		assertThat(plan.safetyGuidance()).isEqualTo(LEGACY_STANDARD_SAFETY);
		assertThat(plan.version()).isZero();
		assertThat(plan.updatedAt()).isEqualTo(NOW);
		verifyNoInteractions(focuses, actions, activities);
		when(families.findBySupportPlanIdOrderByOrdinal(planId)).thenReturn(List.of(
				new SupportPlanTemplateFamilyEntity(UUID.randomUUID(), planId, 1,
						"DEPRESSIVE_PROFESSIONAL_ADJUNCT", "DEPRESSIVE_SYMPTOMS", "Trọng tâm riêng",
						LEGACY_RATIONALE, "USER_PREFERENCE_AND_CARE_POLICY")));
		assertThat(v3.get(userId, planId).focuses().getFirst().rationale()).isEqualTo(LEGACY_RATIONALE);
	}

	@Test
	void doesNotDecodeNativeAuthoredCopyOrUnknownSimilarText() {
		var plan = SupportPlanEntity.nativeDraft(planId, userId, UUID.randomUUID(),
				"mb-support-routing-capstone-v2", NOW, "support-plan-v3-actions-v1", "PLUS", "DEMO",
				"service-entitlement-v1", 1, NOW, "DOMAIN_AWARE_WELLBEING_SUPPORT", LEGACY_RATIONALE,
				"NEGATIVE_SAFETY_SCREEN", "NO_SAFETY_SIGNAL", "safety-v1", "STANDARD_SAFETY_REMINDER",
				LEGACY_STANDARD_SAFETY, 1, NOW);
		UUID focusId = UUID.randomUUID();
		var focus = new SupportPlanFocusEntity(focusId, planId, userId, 1, "Trọng tâm", LEGACY_RATIONALE, NOW);
		var action = new PlanActionEntity(UUID.randomUUID(), planId, focusId, userId, 1,
				"ROUTINE_SELF_MANAGEMENT", "WELLBEING_ROUTINE", LEGACY_RATIONALE, null,
				new Provenance("USER_SELECTED", null, null, NOW), new Presentation("Bước nhỏ", null, null, null), NOW);
		when(plans.findByIdAndUserId(planId, userId)).thenReturn(Optional.of(plan));
		when(focuses.findBySupportPlanIdAndUserIdOrderByOrdinal(planId, userId)).thenReturn(List.of(focus));
		when(actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(planId, userId)).thenReturn(List.of(action));

		var view = v3.get(userId, planId);

		assertThat(view.focuses().getFirst().rationale()).isEqualTo(LEGACY_RATIONALE);
		assertThat(view.focuses().getFirst().actions().getFirst().rationale()).isEqualTo(LEGACY_RATIONALE);
		assertThat(view.safety().guidance()).isEqualTo(LEGACY_STANDARD_SAFETY);
		var unknown = legacyPlan(LEGACY_RATIONALE + " Khác.", "NEGATIVE_SAFETY_SCREEN",
				"STANDARD_SAFETY_REMINDER", LEGACY_STANDARD_SAFETY + " Khác.");
		assertThat(SupportPlanService.displayRationale(unknown)).isEqualTo(unknown.rationaleText());
		assertThat(SupportPlanService.displaySafetyGuidance(unknown)).isEqualTo(unknown.safetyGuidance());
	}

	@Test
	void preservesReadableSnapshotsAndPositiveSafetyCopyVerbatim() {
		var readable = legacyPlan(RATIONALE, "NEGATIVE_SAFETY_SCREEN", "STANDARD_SAFETY_REMINDER", STANDARD_SAFETY);
		assertThat(SupportPlanService.displayRationale(readable)).isEqualTo(RATIONALE);
		assertThat(SupportPlanService.displaySafetyGuidance(readable)).isEqualTo(STANDARD_SAFETY);
		var positive = legacyPlan(LEGACY_RATIONALE, "POSITIVE_SAFETY_SCREEN", "REVIEW_SAFETY_GUIDANCE",
				LEGACY_STANDARD_SAFETY);
		assertThat(SupportPlanService.displaySafetyGuidance(positive)).isEqualTo(LEGACY_STANDARD_SAFETY);
	}

	@Test
	void unknownPolicyAndRationaleCodeDoNotApplyTheEncodingErratum() {
		var plan = mock(SupportPlanEntity.class);
		when(plan.modelVersion()).thenReturn(2);
		when(plan.selectionPolicyVersion()).thenReturn("another-policy");
		when(plan.rationaleText()).thenReturn(LEGACY_RATIONALE);
		when(plan.safetyGuidance()).thenReturn(LEGACY_STANDARD_SAFETY);
		assertThat(SupportPlanService.displayRationale(plan)).isEqualTo(LEGACY_RATIONALE);
		assertThat(SupportPlanService.displaySafetyGuidance(plan)).isEqualTo(LEGACY_STANDARD_SAFETY);
		when(plan.selectionPolicyVersion()).thenReturn(SupportPlanService.SELECTION_POLICY_VERSION);
		when(plan.rationaleCode()).thenReturn("OTHER_RATIONALE");
		assertThat(SupportPlanService.displayRationale(plan)).isEqualTo(LEGACY_RATIONALE);
	}

	private StoredPlan stored(SupportPlanEntity plan) {
		return new StoredPlan(plan, List.of(), List.of());
	}

	private SupportPlanEntity legacyPlan(String rationale, String safetyStatus, String guidanceCode, String guidance) {
		return new SupportPlanEntity(planId, userId, UUID.randomUUID(), "mb-support-routing-capstone-v2",
				NOW, "content-eligibility-v1", NOW, "PLUS", "DEMO", "service-entitlement-v1", 1,
				NOW, rationale, safetyStatus, "NO_SAFETY_SIGNAL", "safety-v1", guidanceCode, guidance, 1, NOW);
	}
}
