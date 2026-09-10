package com.mentalbridge.care.support;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;

@Component
public class SupportRoutingPolicy {

	public Resolution resolve(ScreeningLevel phq9Level, SafetyStatus phq9Safety, ScreeningLevel gad7Level) {
		if (phq9Safety == SafetyStatus.POSITIVE_SAFETY_SCREEN) {
			return new Resolution(SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED,
					List.of(SupportReasonCode.PHQ9_SAFETY_SCREEN_POSITIVE));
		}

		var reasons = new ArrayList<SupportReasonCode>(2);
		if (moderateOrHigher(phq9Level)) reasons.add(SupportReasonCode.PHQ9_MODERATE_OR_HIGHER);
		if (moderateOrHigher(gad7Level)) reasons.add(SupportReasonCode.GAD7_MODERATE_OR_HIGHER);
		if (!reasons.isEmpty()) {
			return new Resolution(SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED, reasons);
		}
		return new Resolution(SupportTier.SELF_GUIDED_SUPPORT,
				List.of(SupportReasonCode.ALL_SCREENING_LEVELS_MINIMAL_OR_MILD));
	}

	private boolean moderateOrHigher(ScreeningLevel level) {
		return level == ScreeningLevel.MODERATE || level == ScreeningLevel.MODERATELY_SEVERE
				|| level == ScreeningLevel.SEVERE;
	}

	public record Resolution(SupportTier tier, List<SupportReasonCode> reasons) {
		public Resolution {
			reasons = List.copyOf(reasons);
		}
	}
}
