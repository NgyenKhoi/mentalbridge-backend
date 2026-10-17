package com.mentalbridge.consultation.discovery;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.mentalbridge.consultation.specialist.SupportArea;

@Component
class ScreeningContextResolver {

	private static final Set<String> SCREENING_LEVELS = Set.of(
			"MINIMAL", "MILD", "MODERATE", "MODERATELY_SEVERE", "SEVERE");
	private static final Set<String> SUPPORT_PATHWAYS = Set.of(
			"SELF_GUIDED_SUPPORT", "PROFESSIONAL_SUPPORT_RECOMMENDED");

	private final CareSupportEvaluationClient care;

	ScreeningContextResolver(CareSupportEvaluationClient care) {
		this.care = care;
	}

	Optional<ScreeningContext> resolve(UUID supportEvaluationId, String bearerToken) {
		try {
			var evaluation = care.get(supportEvaluationId, "Bearer " + bearerToken);
			if (evaluation == null || !supportEvaluationId.equals(evaluation.supportEvaluationId())
					|| evaluation.policyVersion() == null || evaluation.policyVersion().isBlank()
					|| evaluation.contributingDomains() == null || evaluation.contributingDomains().isEmpty()) {
				return Optional.empty();
			}
			var priorities = new EnumMap<SupportArea, Integer>(SupportArea.class);
			for (var contribution : evaluation.contributingDomains()) {
				if (contribution == null || !SCREENING_LEVELS.contains(contribution.screeningLevel())
						|| !SUPPORT_PATHWAYS.contains(contribution.supportPathway())) return Optional.empty();
				SupportArea area;
				try { area = SupportArea.valueOf(contribution.domain()); }
				catch (IllegalArgumentException exception) { return Optional.empty(); }
				int priority = "PROFESSIONAL_SUPPORT_RECOMMENDED".equals(contribution.supportPathway()) ? 2 : 1;
				priorities.merge(area, priority, Math::max);
			}
			return priorities.isEmpty() ? Optional.empty()
					: Optional.of(new ScreeningContext(evaluation.policyVersion(), Map.copyOf(priorities)));
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	record ScreeningContext(String policyVersion, Map<SupportArea, Integer> priorities) { }
}
