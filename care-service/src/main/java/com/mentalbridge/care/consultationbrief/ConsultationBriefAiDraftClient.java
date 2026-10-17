package com.mentalbridge.care.consultationbrief;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class ConsultationBriefAiDraftClient {

	private static final Set<String> PROVIDERS = Set.of("DETERMINISTIC_FAKE", "GEMINI", "OPENAI", "BEDROCK");
	private final JournalConsultationBriefDraftHttpClient http;

	public ConsultationBriefAiDraftClient(JournalConsultationBriefDraftHttpClient http) {
		this.http = http;
	}

	public DraftProviderResponse draft(DraftProviderRequest request, String bearerToken, UUID correlationId) {
		var response = http.draft("Bearer " + bearerToken, correlationId.toString(), request);
		if (!valid(response)) throw new InvalidDraftProviderResponseException();
		return response;
	}

	private boolean valid(DraftProviderResponse value) {
		return value != null && text(value.currentSituation(), 1000) && value.userGoals() != null
				&& value.userGoals().size() >= 1 && value.userGoals().size() <= 5
				&& value.userGoals().stream().allMatch(goal -> text(goal, 200))
				&& "consultation-brief-ai-source-v1".equals(value.sourceSetVersion())
				&& text(value.consentPolicyVersion(), 64) && text(value.servicePlan(), 16)
				&& text(value.entitlementSource(), 32) && text(value.entitlementPolicyVersion(), 96)
				&& value.entitlementVersion() != null && value.entitlementVersion() >= 0
				&& text(value.routingPolicyVersion(), 96) && text(value.providerApprovalVersion(), 96)
				&& PROVIDERS.contains(value.provider()) && text(value.model(), 128)
				&& "consultation-brief-draft-v1".equals(value.promptVersion())
				&& Integer.valueOf(1).equals(value.schemaVersion());
	}

	private boolean text(String value, int maximum) {
		return value != null && !value.isBlank() && value.length() <= maximum;
	}

	public record DraftProviderRequest(UUID appointmentId, UUID consultationBriefId, long consultationBriefVersion,
			UUID supportEvaluationId, String currentSituation, List<String> userGoals,
			List<ConsultationBriefService.ScreeningContext> screeningContext, String sourceSetVersion) { }

	public record DraftProviderResponse(String currentSituation, List<String> userGoals, String sourceSetVersion,
			String consentPolicyVersion, String servicePlan, String entitlementSource,
			String entitlementPolicyVersion, Long entitlementVersion, String routingPolicyVersion,
			String providerApprovalVersion, String provider, String model, String promptVersion,
			Integer schemaVersion, Long latencyMs, Long inputTokens, Long outputTokens,
			Long estimatedCostMicroUsd) { }

	public static final class InvalidDraftProviderResponseException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
