package com.mentalbridge.care.support;

import org.springframework.stereotype.Component;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;

@Component
public class DomainAwareSupportPolicy {

	public DomainResolution resolve(String instrument, ScreeningLevel level) {
		var domain = switch (instrument) {
			case "PHQ9" -> ScreeningDomain.DEPRESSIVE_SYMPTOMS;
			case "GAD7" -> ScreeningDomain.ANXIETY_SYMPTOMS;
			default -> throw new IllegalArgumentException("Unsupported screening instrument");
		};
		var pathway = switch (level) {
			case MINIMAL, MILD -> DomainSupportPathway.SELF_GUIDED_SUPPORT;
			case MODERATE, MODERATELY_SEVERE, SEVERE -> DomainSupportPathway.PROFESSIONAL_SUPPORT_RECOMMENDED;
		};
		return new DomainResolution(domain, pathway,
				DomainReasonCode.valueOf(instrument + "_LEVEL_" + level.name()));
	}

	public SafetyReasonCode safetyReason(SafetyStatus status) {
		return switch (status) {
			case NEGATIVE_SAFETY_SCREEN -> SafetyReasonCode.PHQ9_ITEM9_NEGATIVE;
			case POSITIVE_SAFETY_SCREEN -> SafetyReasonCode.PHQ9_ITEM9_POSITIVE;
			case NOT_APPLICABLE -> throw new IllegalArgumentException("PHQ-9 safety status must be applicable");
		};
	}

	public record DomainResolution(ScreeningDomain domain, DomainSupportPathway pathway,
			DomainReasonCode reasonCode) { }
}
