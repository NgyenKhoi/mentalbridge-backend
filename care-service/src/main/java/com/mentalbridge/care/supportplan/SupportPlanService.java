package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.entitlement.EntitlementClient;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationService;
import com.mentalbridge.care.support.SupportEvaluationV2Service;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.ResourceDraft;
import com.mentalbridge.care.supportplan.SupportPlanWriter.StoredPlan;

@Service
public class SupportPlanService {

	public static final String SELECTION_POLICY_VERSION = "mb-support-plan-selection-v1";
	private static final String RATIONALE = "MentalBridge đã ghép các lựa chọn hỗ trợ sức khỏe tổng quát theo từng miền sàng lọc, chính sách Care và phiên bản tài nguyên đã được duyệt. Đây không phải chẩn đoán hay kế hoạch điều trị.";
	private static final String STANDARD_SAFETY = "Nếu tình trạng của bạn thay đổi hoặc bạn cảm thấy không an toàn, hãy chủ động tìm hỗ trợ trực tiếp phù hợp tại khu vực bạn đã chọn.";
	private static final String DISCLAIMER = "SupportPlan này hỗ trợ sức khỏe tổng quát và tự quản lý; không phải chẩn đoán, đơn thuốc hoặc kế hoạch điều trị, và không thay thế hỗ trợ an toàn hay hỗ trợ chuyên môn.";

	private final EntitlementClient entitlements;
	private final SupportEvaluationV2Service evaluations;
	private final ResourceEligibilityClient eligibility;
	private final SupportPlanPolicy policy;
	private final SupportPlanWriter writer;
	private final Clock clock;

	public SupportPlanService(EntitlementClient entitlements, SupportEvaluationV2Service evaluations,
			ResourceEligibilityClient eligibility, SupportPlanPolicy policy, SupportPlanWriter writer, Clock clock) {
		this.entitlements = entitlements;
		this.evaluations = evaluations;
		this.eligibility = eligibility;
		this.policy = policy;
		this.writer = writer;
		this.clock = clock;
	}

	public SupportPlanView propose(UUID userId, String bearerToken, String idempotencyKey,
			UUID correlationId, ProposeCommand command) {
		String requestHash = hash(command.sourceSupportEvaluationId());
		var replay = writer.request(userId, idempotencyKey);
		if (replay != null) {
			if (!replay.getRequestHash().equals(requestHash)) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"Idempotency-Key was already used with different SupportPlan evidence");
			}
			return view(writer.required(userId, replay.getPlanId()));
		}

		CurrentEntitlementResponse entitlement = entitlements.current(userId, bearerToken, correlationId);
		if (entitlement.packageCode() == ServicePackage.FREE) {
			throw new ApiException(HttpStatus.FORBIDDEN, "SUPPORT_PLAN_ENTITLEMENT_REQUIRED",
					"A current PLUS or PREMIUM entitlement is required for SupportPlan");
		}
		var evaluation = evaluations.getCurrentCompatible(userId, command.sourceSupportEvaluationId());
		var proposalRequest = policy.request(evaluation);
		var resolved = eligibility.resolve(proposalRequest.batch(), bearerToken, correlationId);
		var proposal = policy.compose(proposalRequest, resolved);
		boolean safetyPositive = evaluation.safetyEvidence().status() == SafetyStatus.POSITIVE_SAFETY_SCREEN;
		var stored = writer.persist(userId, idempotencyKey, requestHash, evaluation, entitlement, proposal,
				RATIONALE, safetyPositive ? "REVIEW_SAFETY_GUIDANCE" : "STANDARD_SAFETY_REMINDER",
				safetyPositive ? SupportEvaluationService.SAFETY_FALLBACK : STANDARD_SAFETY, clock.instant());
		return view(stored);
	}

	public SupportPlanView current(UUID userId) {
		return view(writer.current(userId));
	}

	private SupportPlanView view(StoredPlan stored) {
		var plan = stored.plan();
		var families = stored.families().stream().map(family -> new TemplateFamilyView(family.family(),
				family.templateVersion(), family.targetDomain())).toList();
		var slots = stored.slots().stream().map(storedSlot -> new SlotView(storedSlot.slot().slotKey(),
				storedSlot.slot().slotKind(), storedSlot.slot().targetDomain(), storedSlot.slot().purposeCode(),
				resource(storedSlot.slot().selectedResource()),
				storedSlot.alternatives().stream().map(value -> resource(value.resource())).toList())).toList();
		return new SupportPlanView(plan.id(), plan.status(), plan.version(),
				new SourceView(plan.supportEvaluationId(), 2, plan.evaluationPolicyVersion(), plan.evaluatedAt(),
						plan.selectionPolicyVersion(), plan.resourcePolicyVersion(), plan.resourcesResolvedAt()),
				new EntitlementView(plan.entitlementPackage(), plan.entitlementSource(),
						plan.entitlementPolicyVersion(), plan.entitlementVersion(), plan.entitlementDecidedAt()),
				new RationaleView(plan.rationaleCode(), plan.rationaleText()),
				new SafetyView(plan.safetyStatus(), plan.safetyReasonCode(), plan.safetyPolicyVersion(),
						plan.safetyGuidanceCode(), plan.safetyGuidance()),
				families, slots, plan.selectedResourceCount(), plan.createdAt(), plan.updatedAt(),
				"WELLBEING_SUPPORT_NOT_TREATMENT", DISCLAIMER);
	}

	private ResourceView resource(ResourceDraft resource) {
		return new ResourceView(resource.resourceId(), Long.toString(resource.contentVersion()),
				resource.publicationId(), resource.role(), resource.category(), resource.title(), resource.summary(),
				resource.externalUrl());
	}

	private String hash(UUID evaluationId) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(("supportEvaluationId=" + evaluationId).getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public record ProposeCommand(UUID sourceSupportEvaluationId) { }
	public record SourceView(UUID supportEvaluationId, int evaluationVersion, String evaluationPolicyVersion,
			Instant evaluatedAt, String selectionPolicyVersion, String resourceEligibilityPolicyVersion,
			Instant resourcesResolvedAt) { }
	public record EntitlementView(String packageCode, String source, String policyVersion, long version,
			Instant decidedAt) { }
	public record RationaleView(String code, String text) { }
	public record SafetyView(String status, String reasonCode, String policyVersion, String guidanceCode,
			String guidance) { }
	public record TemplateFamilyView(String family, int templateVersion, String targetDomain) { }
	public record ResourceView(UUID resourceId, String contentVersion, UUID publicationId, String role,
			String category, String title, String summary, String externalUrl) { }
	public record SlotView(String slotId, String kind, String targetDomain, String purposeCode,
			ResourceView selectedResource, List<ResourceView> allowedAlternatives) { }
	public record SupportPlanView(UUID supportPlanId, String status, long version, SourceView source,
			EntitlementView entitlement, RationaleView rationale, SafetyView safety,
			List<TemplateFamilyView> templateFamilies, List<SlotView> slots, int selectedResourceCount,
			Instant createdAt, Instant updatedAt, String disclaimerCode, String disclaimer) { }
}
