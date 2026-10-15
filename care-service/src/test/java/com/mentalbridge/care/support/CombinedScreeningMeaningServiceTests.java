package com.mentalbridge.care.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationRepository.Evidence;
import com.mentalbridge.care.support.SupportEvaluationRepository.Guidance;

class CombinedScreeningMeaningServiceTests {

	private static final String SOURCE_POLICY = "mb-support-routing-capstone-v1";
	private final SupportEvaluationRepository evaluations = mock(SupportEvaluationRepository.class);
	private final CombinedScreeningMeaningService service = new CombinedScreeningMeaningService(evaluations,
			new SupportRoutingPolicy(), new DraftCombinedScreeningMeaningCatalog());
	private final UUID userId = UUID.randomUUID();
	private final UUID evaluationId = UUID.randomUUID();
	private final UUID phq9Id = UUID.randomUUID();
	private final UUID gad7Id = UUID.randomUUID();
	private final Instant evaluatedAt = Instant.parse("2026-09-10T08:00:00Z");

	@Test
	void preservesTheOwnedExactHistoricalPairAndSeparatesSourceFromDraftPresentation() {
		prepare(ScreeningLevel.MILD, ScreeningLevel.MODERATE, SafetyStatus.NEGATIVE_SAFETY_SCREEN);
		var result = service.get(userId, evaluationId);
		assertThat(result.supportEvaluationId()).isEqualTo(evaluationId);
		assertThat(result.sourcePolicyVersion()).isEqualTo(SOURCE_POLICY);
		assertThat(result.evaluatedAt()).isEqualTo(evaluatedAt);
		assertThat(result.contentVersion()).isEqualTo("mb-combined-screening-meaning-vi-vn-v1-draft.2");
		assertThat(result.presentationPolicyVersion()).isEqualTo("mb-combined-screening-meaning-v1");
		assertThat(result.evidence()).extracting(CombinedScreeningMeaningService.EvidenceView::assessmentId)
				.containsExactly(phq9Id, gad7Id);
		assertThat(result.baseMeaning().code()).isEqualTo("PHQ9_MILD_GAD7_MODERATE");
		assertThat(result.supportTier()).isEqualTo(SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED);
		assertThat(result.presentationPriority()).isEqualTo(CombinedScreeningMeaningService.PresentationPriority.PROFESSIONAL_SUPPORT);
		assertThat(result.nextStep().text()).isEqualTo("Existing approved next step");
		assertThat(result.safetyGuidance()).isNull();
		verify(evaluations, never()).currentPolicyVersion();
	}

	@ParameterizedTest
	@MethodSource("com.mentalbridge.care.support.DraftCombinedScreeningMeaningCatalogTests#pairs")
	void positiveIndependentSafetyWinsWithoutChangingBaseMeaningOrLevels(ScreeningLevel phq9, ScreeningLevel gad7) {
		prepare(phq9, gad7, SafetyStatus.POSITIVE_SAFETY_SCREEN);
		var result = service.get(userId, evaluationId);
		assertThat(result.supportTier()).isEqualTo(SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED);
		assertThat(result.presentationPriority()).isEqualTo(CombinedScreeningMeaningService.PresentationPriority.SAFETY);
		assertThat(result.baseMeaning().code()).isEqualTo("PHQ9_" + phq9 + "_GAD7_" + gad7);
		assertThat(result.evidence().getFirst().screeningLevel()).isEqualTo(phq9);
		assertThat(result.evidence().get(1).screeningLevel()).isEqualTo(gad7);
		assertThat(result.evidence().getFirst().safetyStatus()).isEqualTo(SafetyStatus.POSITIVE_SAFETY_SCREEN);
		assertThat(result.safetyGuidance()).isEqualTo(SupportEvaluationService.SAFETY_FALLBACK);
	}

	@Test
	void absentSafetyGuidanceUsesTheExistingReviewedLocalFallback() {
		prepare(ScreeningLevel.MINIMAL, ScreeningLevel.MINIMAL, SafetyStatus.POSITIVE_SAFETY_SCREEN);
		when(evaluations.guidance(SOURCE_POLICY, SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED)).thenReturn(Optional.empty());
		var result = service.get(userId, evaluationId);
		assertThat(result.nextStep().code()).isEqualTo("REVIEW_SAFETY_GUIDANCE");
		assertThat(result.safetyGuidance()).isEqualTo(SupportEvaluationService.SAFETY_FALLBACK);
		assertThat(result.baseMeaning().supportTier()).isEqualTo(SupportTier.SELF_GUIDED_SUPPORT);
	}

	@Test
	void unreadableSafetyCopyCannotSuppressTheApprovedLocalFallback() {
		prepare(ScreeningLevel.MINIMAL, ScreeningLevel.MINIMAL, SafetyStatus.POSITIVE_SAFETY_SCREEN);
		when(evaluations.guidance(SOURCE_POLICY, SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED)).thenReturn(Optional.of(
				new Guidance("REVIEW_SAFETY_GUIDANCE", "broken-copy-v1", "Unreadable \uFFFD text", "Boundary", null)));
		var result = service.get(userId, evaluationId);
		assertThat(result.presentationPriority()).isEqualTo(CombinedScreeningMeaningService.PresentationPriority.SAFETY);
		assertThat(result.nextStep().contentVersion()).isEqualTo("mb-safety-guidance-vi-vn-v1");
		assertThat(result.safetyGuidance()).isEqualTo(SupportEvaluationService.SAFETY_FALLBACK);
	}

	@Test
	void missingAndForeignEvaluationsShareOpaqueNotFoundBeforeAnySourceRead() {
		when(evaluations.findByIdAndUserId(evaluationId, userId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.get(userId, evaluationId)).isInstanceOfSatisfying(ApiException.class, failure -> {
			assertThat(failure.status()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(failure.code()).isEqualTo("SUPPORT_EVALUATION_NOT_FOUND");
		});
		verify(evaluations, never()).findEvidence(userId, phq9Id);
	}

	@Test
	void unsupportedHistoricalPolicyIsNotSilentlyReinterpreted() {
		when(evaluations.findByIdAndUserId(evaluationId, userId)).thenReturn(Optional.of(new SupportEvaluationEntity(
				evaluationId, userId, phq9Id, gad7Id, "unsupported-historical-policy", SupportTier.SELF_GUIDED_SUPPORT,
				SupportReasonCode.ALL_SCREENING_LEVELS_MINIMAL_OR_MILD, null, evaluatedAt)));
		assertUnavailable();
		verify(evaluations, never()).findEvidence(userId, phq9Id);
	}

	@Test
	void missingVoidedIneligibleOrWrongInstrumentEvidenceDoesNotInventMeaning() {
		prepare(ScreeningLevel.MILD, ScreeningLevel.MINIMAL, SafetyStatus.NEGATIVE_SAFETY_SCREEN);
		when(evaluations.findEvidence(userId, gad7Id)).thenReturn(Optional.empty());
		assertUnavailable();
		var gad7 = evidence(gad7Id, "GAD7", ScreeningLevel.MINIMAL, SafetyStatus.NOT_APPLICABLE, null);
		when(evaluations.findEvidence(userId, gad7Id)).thenReturn(Optional.of(gad7));
		when(evaluations.isEligible(SOURCE_POLICY, gad7)).thenReturn(false);
		assertUnavailable();
		var voided = new Evidence(gad7Id, gad7.definitionId(), evaluatedAt, "GAD7", gad7.questionnaireVersion(),
				gad7.totalScore(), gad7.screeningLevel(), gad7.scoringVersion(), gad7.safetyStatus(), null);
		when(evaluations.findEvidence(userId, gad7Id)).thenReturn(Optional.of(voided));
		assertUnavailable();
		when(evaluations.findEvidence(userId, gad7Id)).thenReturn(Optional.of(
				evidence(gad7Id, "PHQ9", ScreeningLevel.MINIMAL, SafetyStatus.NEGATIVE_SAFETY_SCREEN, "safety-v1")));
		assertUnavailable();
	}

	@Test
	void inconsistentStoredTierIsUnavailableRatherThanRecomputed() {
		prepare(ScreeningLevel.MILD, ScreeningLevel.MINIMAL, SafetyStatus.NEGATIVE_SAFETY_SCREEN);
		when(evaluations.findByIdAndUserId(evaluationId, userId)).thenReturn(Optional.of(new SupportEvaluationEntity(
				evaluationId, userId, phq9Id, gad7Id, SOURCE_POLICY, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
				SupportReasonCode.PHQ9_MODERATE_OR_HIGHER, null, evaluatedAt)));
		assertUnavailable();
	}

	@Test
	void missingOrUnreadableOrdinaryGuidanceIsUnavailableWithoutDiscardingSourceHistory() {
		prepare(ScreeningLevel.MILD, ScreeningLevel.MINIMAL, SafetyStatus.NEGATIVE_SAFETY_SCREEN);
		when(evaluations.guidance(SOURCE_POLICY, SupportTier.SELF_GUIDED_SUPPORT)).thenReturn(Optional.empty());
		assertUnavailable();
		when(evaluations.guidance(SOURCE_POLICY, SupportTier.SELF_GUIDED_SUPPORT)).thenReturn(Optional.of(
				new Guidance("NEXT", "copy-v1", "Unreadable \uFFFD text", "Boundary", null)));
		assertUnavailable();
	}

	private void prepare(ScreeningLevel phq9Level, ScreeningLevel gad7Level, SafetyStatus safety) {
		var tier = new SupportRoutingPolicy().resolve(phq9Level, safety, gad7Level).tier();
		when(evaluations.findByIdAndUserId(evaluationId, userId)).thenReturn(Optional.of(new SupportEvaluationEntity(
				evaluationId, userId, phq9Id, gad7Id, SOURCE_POLICY, tier, SupportReasonCode.PHQ9_MODERATE_OR_HIGHER,
				null, evaluatedAt)));
		var phq9 = evidence(phq9Id, "PHQ9", phq9Level, safety, "MB-SAFETY-PHQ9-001/1.0-capstone");
		var gad7 = evidence(gad7Id, "GAD7", gad7Level, SafetyStatus.NOT_APPLICABLE, null);
		when(evaluations.findEvidence(userId, phq9Id)).thenReturn(Optional.of(phq9));
		when(evaluations.findEvidence(userId, gad7Id)).thenReturn(Optional.of(gad7));
		when(evaluations.isEligible(SOURCE_POLICY, phq9)).thenReturn(true);
		when(evaluations.isEligible(SOURCE_POLICY, gad7)).thenReturn(true);
		when(evaluations.guidance(SOURCE_POLICY, tier)).thenReturn(Optional.of(new Guidance("EXISTING_NEXT_STEP",
				"existing-copy-v1", "Existing approved next step", "Existing approved boundary",
				tier == SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED ? SupportEvaluationService.SAFETY_FALLBACK : null)));
	}

	private Evidence evidence(UUID id, String instrument, ScreeningLevel level, SafetyStatus safety, String safetyPolicy) {
		return new Evidence(id, UUID.randomUUID(), null, instrument,
				"PHQ9".equals(instrument) ? "phq9-vi-vn-capstone-v2" : "gad7-vi-vn-adult-v1",
				"PHQ9".equals(instrument) ? 8 : 7, level,
				"PHQ9".equals(instrument) ? "phq9-standard-bands-v1" : "gad7-standard-bands-v1", safety, safetyPolicy);
	}

	private void assertUnavailable() {
		assertThatThrownBy(() -> service.get(userId, evaluationId)).isInstanceOfSatisfying(ApiException.class, failure -> {
			assertThat(failure.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(failure.code()).isEqualTo("COMBINED_SCREENING_MEANING_UNAVAILABLE");
		});
	}
}
