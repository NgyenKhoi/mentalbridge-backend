package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.CompanionRequest;
import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.CompanionResponse;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionPresentationMetadataView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionProvenanceView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanActionView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanFocusView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.SafetyView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.SupportPlanV3View;

@ExtendWith(MockitoExtension.class)
class SupportPlanContextualCompanionServiceTests {

	@Mock private SupportPlanV3Service plans;
	@Mock private SupportPlanCompanionClient companion;
	@Mock private SupportPlanCompanionAdmissionGuard admission;

	private SupportPlanContextualCompanionService service;
	private UUID userId;
	private UUID planId;
	private UUID actionId;

	@BeforeEach
	void setUp() {
		userId = UUID.randomUUID();
		planId = UUID.randomUUID();
		actionId = UUID.randomUUID();
		service = new SupportPlanContextualCompanionService(plans, companion, admission);
	}

	@Test
	void buildsOnlyTheApprovedActionContextAndBindsTheReturnedIdentity() {
		admitProviderRequest();
		when(plans.get(userId, planId)).thenReturn(plan("ACTIVE", actionId, "RESOURCE", "ACTIVE"));
		when(companion.message(any(), eq("jwt-value"), any())).thenReturn(new CompanionResponse(
				"support-plan-companion-message-v1", "EXPLAIN_ACTION", "TODAY", "Giải thích đã kiểm duyệt.",
				"GENERATED", null, "support-plan-companion-policy-v1", "support-plan-companion-prompt-v1",
				"support-plan-companion-template-v1", "OPENAI", "gpt-test", null));

		var result = service.explain(userId, planId, actionId, "TODAY", "jwt-value", UUID.randomUUID());

		assertThat(result.supportPlanId()).isEqualTo(planId);
		assertThat(result.actionId()).isEqualTo(actionId);
		assertThat(result.surface()).isEqualTo("TODAY");
		var request = ArgumentCaptor.forClass(CompanionRequest.class);
		verify(companion).message(request.capture(), eq("jwt-value"), any());
		assertThat(request.getValue().requestSource()).isEqualTo("USER_INITIATED");
		assertThat(request.getValue().intent()).isEqualTo("EXPLAIN_ACTION");
		assertThat(request.getValue().context().action().title()).isEqualTo("Bài thở 4–6");
		assertThat(request.getValue().context().action().rationale()).isEqualTo("Một bước được Care phê duyệt.");
		assertThat(request.getValue().context().action().availability()).isEqualTo("AVAILABLE");
	}

	@Test
	void describesADraftPlanActionAsUnavailableInsteadOfFabricatingAvailability() {
		admitProviderRequest();
		when(plans.get(userId, planId)).thenReturn(plan("DRAFT", actionId, "JOURNAL", "DRAFT"));
		when(companion.message(any(), eq("jwt-value"), any())).thenAnswer(invocation -> {
			CompanionRequest request = invocation.getArgument(0);
			assertThat(request.context().action().availability()).isEqualTo("UNAVAILABLE");
			return new CompanionResponse("support-plan-companion-message-v1", "EXPLAIN_ACTION", "PLAN",
					"Thông tin cho hoạt động hiện chưa sẵn sàng.", "TEMPLATE", null,
					"support-plan-companion-policy-v1", "support-plan-companion-prompt-v1",
					"support-plan-companion-template-v1", null, null, "PROVIDER_DISABLED");
		});

		var result = service.explain(userId, planId, actionId, "PLAN", "jwt-value", UUID.randomUUID());

		assertThat(result.delivery()).isEqualTo("TEMPLATE");
	}

	@Test
	void failsClosedForAnActionOutsideTheOwnedPlan() {
		when(plans.get(userId, planId)).thenReturn(plan("ACTIVE", actionId, "RESOURCE", "ACTIVE"));

		assertThatThrownBy(() -> service.explain(userId, planId, UUID.randomUUID(), "PLAN", "jwt", UUID.randomUUID()))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void rejectsTodayForADraftAndUnsupportedActions() {
		when(plans.get(userId, planId)).thenReturn(plan("DRAFT", actionId, "RESOURCE", "DRAFT"));
		assertThatThrownBy(() -> service.explain(userId, planId, actionId, "TODAY", "jwt", UUID.randomUUID()))
				.isInstanceOf(ApiException.class);

		when(plans.get(userId, planId)).thenReturn(plan("ACTIVE", actionId, "REASSESSMENT_SCREENING", "ACTIVE"));
		assertThatThrownBy(() -> service.explain(userId, planId, actionId, "PLAN", "jwt", UUID.randomUUID()))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void returnsQuotaFallbackWithoutProviderCallWhileAnotherRequestIsPending() {
		when(companion.enabled()).thenReturn(true);
		when(plans.get(userId, planId)).thenReturn(plan("ACTIVE", actionId, "RESOURCE", "ACTIVE"));
		when(admission.acquire(userId)).thenReturn(SupportPlanCompanionAdmissionGuard.Permit.pending());
		when(companion.fallback(any(), eq("PROVIDER_QUOTA_LIMITED"))).thenAnswer(invocation ->
				template(invocation.getArgument(0), "PROVIDER_QUOTA_LIMITED"));

		var result = service.explain(userId, planId, actionId, "PLAN", "jwt-value", UUID.randomUUID());

		assertThat(result.delivery()).isEqualTo("TEMPLATE");
		assertThat(result.fallbackReason()).isEqualTo("PROVIDER_QUOTA_LIMITED");
		verify(companion, never()).message(any(), any(), any());
	}

	@Test
	void returnsDependencyFallbackWithoutProviderCallWhenAdmissionStoreIsUnavailable() {
		when(companion.enabled()).thenReturn(true);
		when(plans.get(userId, planId)).thenReturn(plan("ACTIVE", actionId, "RESOURCE", "ACTIVE"));
		when(admission.acquire(userId)).thenReturn(SupportPlanCompanionAdmissionGuard.Permit.unavailable());
		when(companion.fallback(any(), eq("PROVIDER_UNAVAILABLE"))).thenAnswer(invocation ->
				template(invocation.getArgument(0), "PROVIDER_UNAVAILABLE"));

		var result = service.explain(userId, planId, actionId, "PLAN", "jwt-value", UUID.randomUUID());

		assertThat(result.delivery()).isEqualTo("TEMPLATE");
		assertThat(result.fallbackReason()).isEqualTo("PROVIDER_UNAVAILABLE");
		verify(companion, never()).message(any(), any(), any());
	}

	private CompanionResponse template(CompanionRequest request, String reason) {
		return new CompanionResponse("support-plan-companion-message-v1", request.intent(), request.surface(),
				"Phản hồi dự phòng.", "TEMPLATE", null, "support-plan-companion-policy-v1",
				"support-plan-companion-prompt-v1", "support-plan-companion-template-v1", null, null, reason);
	}

	private void admitProviderRequest() {
		when(companion.enabled()).thenReturn(true);
		when(admission.acquire(userId)).thenReturn(SupportPlanCompanionAdmissionGuard.Permit.admitted(() -> { }));
	}

	private SupportPlanV3View plan(String status, UUID id, String actionType, String actionStatus) {
		var now = Instant.parse("2026-10-14T00:00:00Z");
		var action = new PlanActionView(id.toString(), actionType, 1, "NATIVE_V3", actionStatus, "PRIMARY",
				"SELF_MANAGEMENT", "Một bước được Care phê duyệt.", 1, null,
				new ActionProvenanceView("SYSTEM_POLICY", "test", "v1", now),
				new ActionPresentationMetadataView("Bài thở 4–6", null, null, null), List.of(), now, now, now, null);
		return new SupportPlanV3View(planId, 3, "support-plan-policy-v3", "NATIVE_V3", status, 1,
				new SafetyView("ROUTINE", null, "safety-v1", null, null),
				List.of(new PlanFocusView(UUID.randomUUID().toString(), "Tập trung", null, null, 1, List.of(action))),
				now, now, now, null, null, null, null, "WELLBEING_SUPPORT_NOT_TREATMENT", "Ranh giới.", null);
	}
}
