package com.mentalbridge.care.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;

class DomainAwareSupportPolicyTests {

	private final DomainAwareSupportPolicy policy = new DomainAwareSupportPolicy();

	@Test
	void resolvesEveryInstrumentLevelToItsOwnDomainPathwayAndReason() {
		assertThat(policy.resolve("PHQ9", ScreeningLevel.MINIMAL))
				.isEqualTo(new DomainAwareSupportPolicy.DomainResolution(ScreeningDomain.DEPRESSIVE_SYMPTOMS,
						DomainSupportPathway.SELF_GUIDED_SUPPORT, DomainReasonCode.PHQ9_LEVEL_MINIMAL));
		assertThat(policy.resolve("PHQ9", ScreeningLevel.MODERATELY_SEVERE))
				.isEqualTo(new DomainAwareSupportPolicy.DomainResolution(ScreeningDomain.DEPRESSIVE_SYMPTOMS,
						DomainSupportPathway.PROFESSIONAL_SUPPORT_RECOMMENDED,
						DomainReasonCode.PHQ9_LEVEL_MODERATELY_SEVERE));
		assertThat(policy.resolve("GAD7", ScreeningLevel.MILD))
				.isEqualTo(new DomainAwareSupportPolicy.DomainResolution(ScreeningDomain.ANXIETY_SYMPTOMS,
						DomainSupportPathway.SELF_GUIDED_SUPPORT, DomainReasonCode.GAD7_LEVEL_MILD));
		assertThat(policy.resolve("GAD7", ScreeningLevel.SEVERE))
				.isEqualTo(new DomainAwareSupportPolicy.DomainResolution(ScreeningDomain.ANXIETY_SYMPTOMS,
						DomainSupportPathway.PROFESSIONAL_SUPPORT_RECOMMENDED, DomainReasonCode.GAD7_LEVEL_SEVERE));
	}

	@Test
	void resolvesSafetySeparatelyFromEveryDomainLevel() {
		assertThat(policy.safetyReason(SafetyStatus.NEGATIVE_SAFETY_SCREEN))
				.isEqualTo(SafetyReasonCode.PHQ9_ITEM9_NEGATIVE);
		assertThat(policy.safetyReason(SafetyStatus.POSITIVE_SAFETY_SCREEN))
				.isEqualTo(SafetyReasonCode.PHQ9_ITEM9_POSITIVE);
		assertThatThrownBy(() -> policy.safetyReason(SafetyStatus.NOT_APPLICABLE))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
