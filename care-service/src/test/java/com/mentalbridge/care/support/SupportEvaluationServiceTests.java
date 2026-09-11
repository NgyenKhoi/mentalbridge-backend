package com.mentalbridge.care.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.support.SupportEvaluationRepository.Evidence;
import com.mentalbridge.care.support.SupportEvaluationRepository.Meaning;

class SupportEvaluationServiceTests {

	@Test
	void safetyEvaluationRetainsMinimumGuidanceWhenOptionalGuidanceLookupIsEmpty() {
		var repository = mock(SupportEvaluationRepository.class);
		var userId = UUID.randomUUID();
		var evaluationId = UUID.randomUUID();
		var phq9Id = UUID.randomUUID();
		var gad7Id = UUID.randomUUID();
		var policyVersion = "mb-support-routing-capstone-v1";
		var evaluatedAt = Instant.parse("2026-09-10T08:00:00Z");
		var evaluation = new SupportEvaluationEntity(evaluationId, userId, phq9Id, gad7Id, policyVersion,
				SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED, SupportReasonCode.PHQ9_SAFETY_SCREEN_POSITIVE, null,
				evaluatedAt);
		var phq9 = new Evidence(phq9Id, UUID.randomUUID(), null, "PHQ9", "phq9-vi-vn-capstone-v2",
				ScreeningLevel.MILD, "phq9-standard-bands-v1", SafetyStatus.POSITIVE_SAFETY_SCREEN,
				"MB-SAFETY-PHQ9-001/1.0-capstone");
		var gad7 = new Evidence(gad7Id, UUID.randomUUID(), null, "GAD7", "gad7-vi-vn-adult-v1",
				ScreeningLevel.MINIMAL, "gad7-standard-bands-v1", SafetyStatus.NOT_APPLICABLE, null);

		when(repository.findByIdAndUserId(evaluationId, userId)).thenReturn(Optional.of(evaluation));
		when(repository.findEvidence(userId, phq9Id)).thenReturn(Optional.of(phq9));
		when(repository.findEvidence(userId, gad7Id)).thenReturn(Optional.of(gad7));
		when(repository.meaning(policyVersion, "PHQ9", ScreeningLevel.MILD))
				.thenReturn(new Meaning("PHQ9_MILD_14D", "mb-screening-meaning-vi-vn-v1", 14,
						"PHQ-9 meaning", "Screening limitation"));
		when(repository.meaning(policyVersion, "GAD7", ScreeningLevel.MINIMAL))
				.thenReturn(new Meaning("GAD7_MINIMAL_14D", "mb-screening-meaning-vi-vn-v1", 14,
						"GAD-7 meaning", "Screening limitation"));
		when(repository.guidance(policyVersion, SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED))
				.thenReturn(Optional.empty());

		var service = new SupportEvaluationService(repository, mock(UserProfileRepository.class),
				new SupportRoutingPolicy(), new ObjectMapper(), Clock.fixed(evaluatedAt, ZoneOffset.UTC));

		var result = service.get(userId, evaluationId);

		assertThat(result.supportTier()).isEqualTo(SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED);
		assertThat(result.safetyGuidance()).isEqualTo(SupportEvaluationService.SAFETY_FALLBACK);
		assertThat(result.nextStep().code()).isEqualTo("REVIEW_SAFETY_GUIDANCE");
		assertThat(result.nextStep().text()).isNotBlank();
		assertThat(result.nextStep().boundary()).isNotBlank();
	}
}
