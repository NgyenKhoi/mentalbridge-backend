package com.mentalbridge.care.support;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationRepository.Evidence;
import com.mentalbridge.care.support.SupportEvaluationRepository.Guidance;

@Service
public class CombinedScreeningMeaningService {

	private static final Set<String> SUPPORTED_SOURCE_POLICIES = Set.of("mb-support-routing-capstone-v1");

	private final SupportEvaluationRepository evaluations;
	private final SupportRoutingPolicy routing;
	private final DraftCombinedScreeningMeaningCatalog catalog;

	public CombinedScreeningMeaningService(SupportEvaluationRepository evaluations, SupportRoutingPolicy routing,
			DraftCombinedScreeningMeaningCatalog catalog) {
		this.evaluations = evaluations;
		this.routing = routing;
		this.catalog = catalog;
	}

	@Transactional(readOnly = true)
	public CombinedMeaningView get(UUID userId, UUID evaluationId) {
		var evaluation = evaluations.findByIdAndUserId(evaluationId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_EVALUATION_NOT_FOUND", "Support evaluation was not found"));
		if (!SUPPORTED_SOURCE_POLICIES.contains(evaluation.policyVersion())) throw unavailable();
		var phq9 = evidence(userId, evaluation.phq9AssessmentId(), "PHQ9", evaluation.policyVersion());
		var gad7 = evidence(userId, evaluation.gad7AssessmentId(), "GAD7", evaluation.policyVersion());
		if (phq9.assessmentId().equals(gad7.assessmentId())
				|| phq9.safetyStatus() == SafetyStatus.NOT_APPLICABLE || phq9.safetyPolicyVersion() == null
				|| gad7.safetyStatus() != SafetyStatus.NOT_APPLICABLE || gad7.safetyPolicyVersion() != null) {
			throw unavailable();
		}
		if (routing.resolve(phq9.screeningLevel(), phq9.safetyStatus(), gad7.screeningLevel()).tier()
				!= evaluation.supportTier()) throw unavailable();
		var base = catalog.resolve(phq9.screeningLevel(), gad7.screeningLevel());
		var guidance = guidance(evaluation);
		var episode = evaluations.findCombinedMeaningEpisode(userId, evaluationId).orElse(null);
		return new CombinedMeaningView(evaluation.id(), evaluation.policyVersion(), evaluation.evaluatedAt(),
				DraftCombinedScreeningMeaningCatalog.PRESENTATION_POLICY_VERSION,
				DraftCombinedScreeningMeaningCatalog.CONTENT_VERSION,
				DraftCombinedScreeningMeaningCatalog.REFERENCE_PERIOD_DAYS,
				episode == null ? null : episode.getEpisodeId(), episode == null ? null : episode.getPurpose(),
				episode == null ? null : episode.getCompletedAt(), episode == null ? null : episode.getSupportEvaluationId(),
				episode == null ? null : episode.getSupportGuideId(),
				List.of(view(phq9), view(gad7)), evaluation.supportTier(),
				new BaseMeaningView(base.code(), base.text(), base.rationale(), base.supportTier()),
				priority(evaluation.supportTier()),
				new SupportEvaluationService.NextStep(guidance.code(), guidance.contentVersion(), guidance.text(),
						guidance.boundary()), guidance.safetyGuidance(), SupportEvaluationService.DISCLAIMER_CODE,
				SupportEvaluationService.DISCLAIMER_TEXT);
	}

	private Evidence evidence(UUID userId, UUID assessmentId, String instrument, String policyVersion) {
		var evidence = evaluations.findEvidence(userId, assessmentId).orElseThrow(this::unavailable);
		if (!assessmentId.equals(evidence.assessmentId()) || !instrument.equals(evidence.instrument())
				|| evidence.voidedAt() != null || evidence.totalScore() == null || !validScore(instrument, evidence.totalScore())
				|| evidence.screeningLevel() == null || evidence.safetyStatus() == null
				|| !readable(evidence.questionnaireVersion(), 32) || !readable(evidence.scoringVersion(), 32)
				|| "GAD7".equals(instrument) && evidence.screeningLevel() == ScreeningLevel.MODERATELY_SEVERE
				|| !evaluations.isEligible(policyVersion, evidence)) throw unavailable();
		return evidence;
	}

	private boolean validScore(String instrument, int score) {
		return score >= 0 && score <= ("PHQ9".equals(instrument) ? 27 : 21);
	}

	private Guidance guidance(SupportEvaluationEntity evaluation) {
		var guidance = evaluations.guidance(evaluation.policyVersion(), evaluation.supportTier()).orElse(null);
		if (validGuidance(guidance, evaluation.supportTier())) return guidance;
		if (evaluation.supportTier() == SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED) {
			return new Guidance("REVIEW_SAFETY_GUIDANCE", "mb-safety-guidance-vi-vn-v1",
					"Ưu tiên xem hướng dẫn an toàn ngay bên dưới và chủ động tìm hỗ trợ trực tiếp nếu bạn cảm thấy không an toàn.",
					"MentalBridge không tự động liên hệ người khác, đặt lịch hoặc chia sẻ dữ liệu.",
					SupportEvaluationService.SAFETY_FALLBACK);
		}
		throw unavailable();
	}

	private boolean validGuidance(Guidance guidance, SupportTier tier) {
		return guidance != null && readable(guidance.code(), 64) && guidance.code().matches("^[A-Z0-9_]+$")
				&& readable(guidance.contentVersion(), 64) && readable(guidance.text(), 1000)
				&& readable(guidance.boundary(), 1000)
				&& (tier == SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED
						? readable(guidance.safetyGuidance(), 1000) : guidance.safetyGuidance() == null);
	}

	private boolean readable(String text, int maximum) {
		return text != null && !text.isBlank() && text.length() <= maximum && text.indexOf('\uFFFD') < 0
				&& text.codePoints().noneMatch(point -> Character.isISOControl(point) && !Character.isWhitespace(point));
	}

	private PresentationPriority priority(SupportTier tier) {
		return switch (tier) {
			case SAFETY_FOLLOW_UP_RECOMMENDED -> PresentationPriority.SAFETY;
			case PROFESSIONAL_SUPPORT_RECOMMENDED -> PresentationPriority.PROFESSIONAL_SUPPORT;
			case SELF_GUIDED_SUPPORT -> PresentationPriority.SELF_GUIDED;
		};
	}

	private EvidenceView view(Evidence evidence) {
		return new EvidenceView(evidence.assessmentId(), evidence.instrument(), evidence.questionnaireVersion(),
				evidence.scoringVersion(), evidence.totalScore(), evidence.screeningLevel(), evidence.safetyStatus());
	}

	private ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "COMBINED_SCREENING_MEANING_UNAVAILABLE",
				"The saved screening context cannot provide a compatible combined meaning");
	}

	public enum PresentationPriority { SAFETY, PROFESSIONAL_SUPPORT, SELF_GUIDED }
	public record CombinedMeaningView(UUID supportEvaluationId, String sourcePolicyVersion, Instant evaluatedAt,
			String presentationPolicyVersion, String contentVersion, int referencePeriodDays,
			UUID episodeId, String episodePurpose, Instant episodeCompletedAt, UUID domainSupportEvaluationId,
			UUID supportGuideId, List<EvidenceView> evidence,
			SupportTier supportTier, BaseMeaningView baseMeaning, PresentationPriority presentationPriority,
			SupportEvaluationService.NextStep nextStep, String safetyGuidance, String disclaimerCode, String disclaimer) { }
	public record EvidenceView(UUID assessmentId, String instrument, String questionnaireVersion, String scoringVersion,
			int totalScore, ScreeningLevel screeningLevel, SafetyStatus safetyStatus) { }
	public record BaseMeaningView(String code, String text, String rationale, SupportTier supportTier) { }
}
