package com.mentalbridge.care.resourceeligibility.generated;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class ResourceEligibilityContract {

	private ResourceEligibilityContract() {
	}

	public enum ScreeningDomain { DEPRESSIVE_SYMPTOMS, ANXIETY_SYMPTOMS }
	public enum EligibilityRole { PRIMARY, ADJUNCT }
	public enum ResourceCategory { BREATHING, MEDITATION, ARTICLE, VIDEO, JOURNALING, COMMUNITY }
	public enum RequiredEligibilityRole { PRIMARY, PRIMARY_OR_ADJUNCT }
	public enum ScreeningInstrument { PHQ_9, GAD_7 }
	public enum ScreeningLevel { MINIMAL, MILD, MODERATE, MODERATELY_SEVERE, SEVERE }
	public enum SupportTier { SELF_GUIDED_SUPPORT, PROFESSIONAL_SUPPORT_RECOMMENDED, SAFETY_FOLLOW_UP_RECOMMENDED }
	public enum ResourceEligibilityOutcome { ELIGIBLE, INELIGIBLE, STALE, WITHDRAWN, NOT_FOUND, UNAVAILABLE }
	public enum ResourceEligibilityReasonCode { ELIGIBLE_MATCH, RESOURCE_NOT_FOUND, CONTENT_VERSION_STALE, NO_ELIGIBILITY_PUBLICATION, DOMAIN_OR_PATHWAY_NOT_ELIGIBLE, PRIMARY_REQUIRED, NOT_YET_EFFECTIVE, EFFECTIVE_WINDOW_ENDED, RESOURCE_ARCHIVED, RESOURCE_NOT_PUBLISHED, LOCALE_MISMATCH, ELIGIBILITY_WITHDRAWN, DEPENDENCY_UNAVAILABLE }

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceEligibilityQuery(
			String requestId,
			String resourceId,
			String contentVersion,
			ScreeningDomain targetDomain,
			RequiredEligibilityRole requiredRole,
			ScreeningInstrument instrument,
			ScreeningLevel screeningLevel,
			SupportTier supportTier,
			String locale) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceEligibilityBatchRequest(List<ResourceEligibilityQuery> requests) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceEligibilityResult(
			String requestId,
			String resourceId,
			String contentVersion,
			ResourceEligibilityOutcome outcome,
			ResourceEligibilityReasonCode reasonCode,
			EligibilityRole role,
			String publicationId,
			ResourceCategory category,
			String title,
			String summary,
			String externalUrl) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceEligibilityBatchResponse(
			String policyVersion,
			String resolvedAt,
			List<ResourceEligibilityResult> results) {
	}
}
