package com.mentalbridge.care.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;

class SupportRoutingPolicyTests {

	private final SupportRoutingPolicy policy = new SupportRoutingPolicy();

	@Test
	void positivePhq9SafetyAlwaysOverridesSymptomBands() {
		for (var phq9 : ScreeningLevel.values()) {
			for (var gad7 : ScreeningLevel.values()) {
				var result = policy.resolve(phq9, SafetyStatus.POSITIVE_SAFETY_SCREEN, gad7);
				assertThat(result.tier()).isEqualTo(SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED);
				assertThat(result.reasons()).containsExactly(SupportReasonCode.PHQ9_SAFETY_SCREEN_POSITIVE);
			}
		}
	}

	@Test
	void moderateOrHigherBandsUseStablePhqThenGadReasonOrder() {
		var result = policy.resolve(ScreeningLevel.MODERATE, SafetyStatus.NEGATIVE_SAFETY_SCREEN,
				ScreeningLevel.SEVERE);

		assertThat(result.tier()).isEqualTo(SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED);
		assertThat(result.reasons()).containsExactly(SupportReasonCode.PHQ9_MODERATE_OR_HIGHER,
				SupportReasonCode.GAD7_MODERATE_OR_HIGHER);
	}

	@Test
	void minimalAndMildBandsRemainSelfGuided() {
		var result = policy.resolve(ScreeningLevel.MILD, SafetyStatus.NEGATIVE_SAFETY_SCREEN,
				ScreeningLevel.MINIMAL);

		assertThat(result.tier()).isEqualTo(SupportTier.SELF_GUIDED_SUPPORT);
		assertThat(result.reasons()).containsExactly(SupportReasonCode.ALL_SCREENING_LEVELS_MINIMAL_OR_MILD);
	}

	@Test
	void coversEveryValidNegativeSafetyPhq9AndGad7BandCombination() {
		var elevated = Set.of(ScreeningLevel.MODERATE, ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.SEVERE);
		var gad7Levels = Set.of(ScreeningLevel.MINIMAL, ScreeningLevel.MILD, ScreeningLevel.MODERATE,
				ScreeningLevel.SEVERE);

		for (var phq9 : ScreeningLevel.values()) {
			for (var gad7 : gad7Levels) {
				var result = policy.resolve(phq9, SafetyStatus.NEGATIVE_SAFETY_SCREEN, gad7);
				var expected = elevated.contains(phq9) || elevated.contains(gad7)
						? SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED : SupportTier.SELF_GUIDED_SUPPORT;
				assertThat(result.tier()).as("PHQ9=%s GAD7=%s", phq9, gad7).isEqualTo(expected);
			}
		}
	}
}
