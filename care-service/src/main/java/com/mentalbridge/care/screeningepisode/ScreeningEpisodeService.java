package com.mentalbridge.care.screeningepisode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.assessment.AssessmentService;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationService;
import com.mentalbridge.care.support.SupportEvaluationV2Service;

@Service
public class ScreeningEpisodeService {

	private static final List<String> PURPOSES = List.of("INITIAL_CHECK", "REASSESSMENT");

	private final ScreeningEpisodeRepository episodes;
	private final UserProfileRepository profiles;
	private final AssessmentService assessments;
	private final SupportEvaluationService presentationEvaluations;
	private final SupportEvaluationV2Service supportEvaluations;
	private final Clock clock;

	public ScreeningEpisodeService(ScreeningEpisodeRepository episodes, UserProfileRepository profiles,
			AssessmentService assessments, SupportEvaluationService presentationEvaluations,
			SupportEvaluationV2Service supportEvaluations, Clock clock) {
		this.episodes = episodes;
		this.profiles = profiles;
		this.assessments = assessments;
		this.presentationEvaluations = presentationEvaluations;
		this.supportEvaluations = supportEvaluations;
		this.clock = clock;
	}

	@Transactional
	public EpisodeView start(UUID userId, String purpose) {
		validatePurpose(purpose);
		requireProfile(userId);
		var open = episodes.findOpenForUpdate(userId, purpose);
		if (!open.isEmpty()) {
			return view(open.getFirst());
		}
		var now = clock.instant();
		return view(episodes.saveAndFlush(new ScreeningEpisodeEntity(UUID.randomUUID(), userId, purpose, now)));
	}

	@Transactional(readOnly = true)
	public EpisodeView current(UUID userId, String purpose) {
		validatePurpose(purpose);
		return episodes.findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(userId, purpose)
				.map(this::view).orElseThrow(this::notFound);
	}

	@Transactional
	public AssessmentService.AssessmentView submitAssessment(UUID userId, UUID episodeId, String instrument,
			String idempotencyKey, UUID correlationId, AssessmentService.SubmissionCommand command) {
		if (!List.of("PHQ9", "GAD7").contains(instrument)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "QUESTIONNAIRE_NOT_FOUND",
					"The requested guided assessment is not available");
		}
		requireProfile(userId);
		var episode = requiredForUpdate(userId, episodeId);
		if ("COMPLETED".equals(episode.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "SCREENING_EPISODE_COMPLETED",
					"A completed screening episode cannot accept new evidence");
		}
		if ("GAD7".equals(instrument) && episode.phq9AssessmentId() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "SCREENING_EPISODE_ORDER_REQUIRED",
					"The guided screening episode must complete PHQ-9 before GAD-7");
		}
		UUID attached = "PHQ9".equals(instrument) ? episode.phq9AssessmentId() : episode.gad7AssessmentId();
		if (attached != null) {
			return assessments.getAuthenticated(userId, attached);
		}
		var assessment = assessments.submitAuthenticated(userId, idempotencyKey, correlationId, command);
		if (!instrument.equals(assessment.instrument()) || assessment.voidedAt() != null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "SCREENING_EPISODE_INSTRUMENT_MISMATCH",
					"The assessment definition does not match the guided screening step");
		}
		episode.attachAssessment(instrument, assessment.assessmentId(), clock.instant());
		episodes.saveAndFlush(episode);
		return assessment;
	}

	@Transactional
	public EvaluationOutcome evaluate(UUID userId, UUID episodeId, UUID correlationId) {
		requireProfile(userId);
		var episode = requiredForUpdate(userId, episodeId);
		if ("COMPLETED".equals(episode.status())) {
			return outcome(episode);
		}
		if (!"READY".equals(episode.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "SCREENING_EPISODE_INCOMPLETE",
					"Both guided assessments are required before support evaluation");
		}
		var command = new SupportEvaluationService.EvaluationCommand(episode.phq9AssessmentId(),
				episode.gad7AssessmentId());
		var presentation = presentationEvaluations.evaluate(userId, "episode-presentation:" + episode.id(),
				correlationId, command);
		var support = supportEvaluations.evaluate(userId, "episode-support:" + episode.id(), correlationId,
				new SupportEvaluationV2Service.EvaluationCommand(episode.phq9AssessmentId(),
						episode.gad7AssessmentId()));
		var completedAt = clock.instant();
		episode.complete(support.supportEvaluationId(), presentation.supportEvaluationId(), completedAt);
		episodes.saveAndFlush(episode);
		return new EvaluationOutcome(view(episode), presentation);
	}

	@Transactional(readOnly = true)
	public EpisodeView requiredCompleted(UUID userId, String purpose) {
		var episode = current(userId, purpose);
		if (!"COMPLETED".equals(episode.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "SCREENING_EPISODE_INCOMPLETE",
					"The current guided screening episode is incomplete");
		}
		return episode;
	}

	@Transactional(readOnly = true)
	public EpisodeView requiredEvaluationContext(UUID userId, UUID supportEvaluationId) {
		return episodes.findByUserIdAndSupportEvaluationId(userId, supportEvaluationId)
				.filter(episode -> "COMPLETED".equals(episode.status()))
				.map(this::view)
				.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
						"SCREENING_EPISODE_REQUIRED",
						"Support evaluation must belong to a completed guided screening episode"));
	}

	private EvaluationOutcome outcome(ScreeningEpisodeEntity episode) {
		return new EvaluationOutcome(view(episode),
				presentationEvaluations.get(episode.userId(), episode.presentationEvaluationId()));
	}

	private ScreeningEpisodeEntity requiredForUpdate(UUID userId, UUID episodeId) {
		return episodes.findOwnedForUpdate(userId, episodeId).orElseThrow(this::notFound);
	}

	private void requireProfile(UUID userId) {
		profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"PROFILE_NOT_FOUND", "Care profile was not found"));
	}

	private void validatePurpose(String purpose) {
		if (!PURPOSES.contains(purpose)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "SCREENING_EPISODE_PURPOSE_INVALID",
					"Screening episode purpose is invalid");
		}
	}

	private EpisodeView view(ScreeningEpisodeEntity episode) {
		return new EpisodeView(episode.id(), episode.purpose(), episode.status(), episode.phq9AssessmentId(),
				episode.gad7AssessmentId(), episode.supportEvaluationId(), episode.presentationEvaluationId(),
				episode.createdAt(), episode.updatedAt(), episode.completedAt(), episode.version());
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "SCREENING_EPISODE_NOT_FOUND",
				"Screening episode was not found");
	}

	public record EpisodeView(UUID episodeId, String purpose, String status, UUID phq9AssessmentId,
			UUID gad7AssessmentId, UUID supportEvaluationId, UUID presentationEvaluationId,
			Instant createdAt, Instant updatedAt, Instant completedAt, long version) { }

	public record EvaluationOutcome(EpisodeView episode,
			SupportEvaluationService.EvaluationView presentationEvaluation) { }
}
