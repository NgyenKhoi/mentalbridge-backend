package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.ActionContext;
import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.CompanionContext;
import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.CompanionRequest;

@Service
public class SupportPlanContextualCompanionService {

	private static final Set<String> SURFACES = Set.of("TODAY", "PLAN");
	private static final Set<String> ACTION_TYPES = Set.of("RESOURCE", "JOURNAL", "EMOTION_CHECK_IN",
			"PROFESSIONAL_SUPPORT");
	private static final Set<String> PLAN_STATUSES = Set.of("DRAFT", "ACTIVE", "PAUSED");

	private final SupportPlanV3Service plans;
	private final SupportPlanCompanionClient companion;

	public SupportPlanContextualCompanionService(SupportPlanV3Service plans, SupportPlanCompanionClient companion) {
		this.plans = plans;
		this.companion = companion;
	}

	public ContextualCompanionMessage explain(UUID userId, UUID supportPlanId, UUID actionId, String surface,
			String bearerToken, UUID correlationId) {
		if (!SURFACES.contains(surface)) throw new ApiException(HttpStatus.BAD_REQUEST,
				"SUPPORT_PLAN_COMPANION_SURFACE_INVALID", "Companion surface is not supported");
		var plan = plans.get(userId, supportPlanId);
		if (!PLAN_STATUSES.contains(plan.status()) || ("TODAY".equals(surface) && "DRAFT".equals(plan.status()))) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_COMPANION_ACTION_UNAVAILABLE",
					"SupportPlan action is not available on this surface");
		}
		var action = plan.focuses().stream().flatMap(focus -> focus.actions().stream())
				.filter(candidate -> candidate.actionId().equals(actionId.toString())).findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_ACTION_NOT_FOUND",
						"SupportPlan action was not found"));
		if (!ACTION_TYPES.contains(action.actionType()) || "ENDED".equals(action.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_COMPANION_ACTION_UNAVAILABLE",
					"SupportPlan action is not eligible for a Companion explanation");
		}
		String title = bounded(action.presentation().title(), 160, true);
		String rationale = bounded(action.rationale(), 320, false);
		String availability = "ACTIVE".equals(action.status()) ? "AVAILABLE" : "UNAVAILABLE";
		var request = new CompanionRequest("support-plan-companion-intent-v1", "support-plan-companion-policy-v1",
				"USER_INITIATED", surface, "EXPLAIN_ACTION",
				new CompanionContext("support-plan-companion-context-v1", "vi-VN", "ACTION",
						new ActionContext(action.actionType(), title, rationale, availability)));
		var response = companion.message(request, bearerToken, correlationId);
		return new ContextualCompanionMessage(response.schemaVersion(), supportPlanId, actionId, response.intent(),
				response.surface(), response.text(), response.delivery(), response.policyVersion(), response.promptVersion(),
				response.templateVersion(), response.provider(), response.model(), response.fallbackReason());
	}

	private String bounded(String value, int maximum, boolean required) {
		if (value == null || value.isBlank() || value.length() > maximum) {
			if (!required) return null;
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_MODEL_INVALID",
					"SupportPlan action model is incomplete or inconsistent");
		}
		return value.trim();
	}

	public record ContextualCompanionMessage(String schemaVersion, UUID supportPlanId, UUID actionId, String intent,
			String surface, String text, String delivery, String policyVersion, String promptVersion,
			String templateVersion, String provider, String model, String fallbackReason) { }
}
