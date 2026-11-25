package com.mentalbridge.care.guidedjourney;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.entitlement.EntitlementClient;
import com.mentalbridge.care.screeningepisode.ScreeningEpisodeService;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.DomainSupportPathway;
import com.mentalbridge.care.support.SupportEvaluationV2Service;
import com.mentalbridge.care.supportguide.SupportGuideService;
import com.mentalbridge.care.supportguide.SupportGuideService.SupportGuideView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanStatusView;

@Service
public class GuidedJourneyService {

	static final String JOURNEY_POLICY_VERSION = "mb-guided-journey-v1";
	private static final String PURPOSE = "INITIAL_CHECK";

	private final ScreeningEpisodeService episodes;
	private final SupportGuideService guides;
	private final SupportEvaluationV2Service evaluations;
	private final SupportPlanV3Service plans;
	private final EntitlementClient entitlements;

	public GuidedJourneyService(ScreeningEpisodeService episodes, SupportGuideService guides,
			SupportEvaluationV2Service evaluations, SupportPlanV3Service plans, EntitlementClient entitlements) {
		this.episodes = episodes;
		this.guides = guides;
		this.evaluations = evaluations;
		this.plans = plans;
		this.entitlements = entitlements;
	}

	public JourneyView current(UUID userId, String bearerToken, UUID correlationId) {
		var context = episodes.journeyContext(userId, PURPOSE);
		boolean assessmentInProgress = context.hasOpen();
		var episode = context.latestCompleted().orElse(null);
		var latest = context.latestCompletedAnyPurpose().orElse(episode);
		var latestScreening = latest == null ? null : LatestScreeningView.of(latest);
		if (episode == null) {
			// A reassessment-only account still gets the safety action; initial-check pointers stay empty (ADR 0023).
			var safety = latest != null && safetyPositive(evaluations.get(userId, latest.supportEvaluationId()))
					? NextAction.REVIEW_SAFETY_SUPPORT : null;
			return new JourneyView(JOURNEY_POLICY_VERSION, Stage.ASSESSMENT_REQUIRED, assessmentInProgress, null,
					null, null, null, safety, null, EntitlementStatus.NOT_REQUIRED, latestScreening);
		}
		UUID guideId = guides.guideIdForEvaluation(userId, latest.supportEvaluationId()).orElse(null);
		var plan = plans.currentOrDraftStatus(userId).orElse(null);
		var decision = nextAction(userId, bearerToken, correlationId,
				evaluations.get(userId, latest.supportEvaluationId()), plan);
		return new JourneyView(JOURNEY_POLICY_VERSION, guideId == null ? Stage.GUIDE_REQUIRED : Stage.GUIDE_READY,
				assessmentInProgress, episode.episodeId(), episode.completedAt(), episode.presentationEvaluationId(),
				guideId, decision.action(), plan == null ? null : plan.supportPlanId(), decision.entitlementStatus(),
				latestScreening);
	}

	public SupportGuideView ensureGuide(UUID userId, String bearerToken, UUID correlationId, UUID episodeId) {
		var episode = episodes.requiredCompletedEpisode(userId, episodeId);
		var current = episodes.journeyContext(userId, PURPOSE).latestCompletedAnyPurpose().orElse(null);
		if (current == null || !current.episodeId().equals(episode.episodeId())) {
			throw new ApiException(HttpStatus.CONFLICT, "SCREENING_EPISODE_NOT_CURRENT",
					"Only the latest completed screening can prepare a Support Guide");
		}
		return guides.ensureForEpisode(userId, bearerToken, correlationId, episode.episodeId(),
				episode.supportEvaluationId());
	}

	private Decision nextAction(UUID userId, String bearerToken, UUID correlationId,
			SupportEvaluationV2Service.EvaluationView evaluation, PlanStatusView plan) {
		if (safetyPositive(evaluation)) {
			return new Decision(NextAction.REVIEW_SAFETY_SUPPORT, EntitlementStatus.NOT_REQUIRED);
		}
		if (evaluation.contributingDomains().stream()
				.anyMatch(domain -> domain.supportPathway() == DomainSupportPathway.PROFESSIONAL_SUPPORT_RECOMMENDED)) {
			return new Decision(NextAction.FIND_SPECIALIST, EntitlementStatus.NOT_REQUIRED);
		}
		if (plan != null) {
			return new Decision("DRAFT".equals(plan.status()) ? NextAction.REVIEW_PLAN_DRAFT
					: NextAction.OPEN_CURRENT_PLAN, EntitlementStatus.NOT_REQUIRED);
		}
		if (!proposalCompatible(userId, evaluation.supportEvaluationId())) {
			return new Decision(NextAction.EXPLORE_GUIDE_RESOURCES, EntitlementStatus.NOT_REQUIRED);
		}
		try {
			var entitlement = entitlements.current(userId, bearerToken, correlationId);
			return new Decision(entitlement.packageCode() == ServicePackage.FREE ? NextAction.EXPLORE_GUIDE_RESOURCES
					: NextAction.START_PLAN_PROPOSAL, EntitlementStatus.VERIFIED);
		}
		catch (ApiException exception) {
			if (!"ENTITLEMENT_UNAVAILABLE".equals(exception.code())) {
				throw exception;
			}
			return new Decision(NextAction.EXPLORE_GUIDE_RESOURCES, EntitlementStatus.UNAVAILABLE);
		}
	}

	private static boolean safetyPositive(SupportEvaluationV2Service.EvaluationView evaluation) {
		return evaluation.safetyEvidence().status() == SafetyStatus.POSITIVE_SAFETY_SCREEN;
	}

	private boolean proposalCompatible(UUID userId, UUID supportEvaluationId) {
		try {
			evaluations.getCurrentCompatible(userId, supportEvaluationId);
			return true;
		}
		catch (ApiException exception) {
			if ("SUPPORT_EVALUATION_STALE".equals(exception.code())) {
				return false;
			}
			throw exception;
		}
	}

	public enum Stage { ASSESSMENT_REQUIRED, GUIDE_REQUIRED, GUIDE_READY }
	public enum NextAction {
		REVIEW_SAFETY_SUPPORT, FIND_SPECIALIST, OPEN_CURRENT_PLAN, REVIEW_PLAN_DRAFT, START_PLAN_PROPOSAL,
		EXPLORE_GUIDE_RESOURCES
	}
	public enum EntitlementStatus { NOT_REQUIRED, VERIFIED, UNAVAILABLE }

	public record JourneyView(String journeyPolicyVersion, Stage stage, boolean assessmentInProgress, UUID episodeId,
			Instant episodeCompletedAt, UUID presentationEvaluationId, UUID supportGuideId, NextAction nextAction,
			UUID currentSupportPlanId, EntitlementStatus entitlementStatus, LatestScreeningView latestScreening) { }

	public record LatestScreeningView(UUID episodeId, String purpose, Instant completedAt,
			UUID presentationEvaluationId, UUID supportEvaluationId) {
		static LatestScreeningView of(ScreeningEpisodeService.EpisodeView episode) {
			return new LatestScreeningView(episode.episodeId(), episode.purpose(), episode.completedAt(),
					episode.presentationEvaluationId(), episode.supportEvaluationId());
		}
	}

	private record Decision(NextAction action, EntitlementStatus entitlementStatus) { }
}
