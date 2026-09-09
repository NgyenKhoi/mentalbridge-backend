package com.mentalbridge.care.assessment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.configuration.AssessmentProperties;
import com.mentalbridge.care.consent.ConsentService;
import com.mentalbridge.care.consent.PrivacyDisclosureService;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.shared.ApiException.FieldViolation;

@Service
public class AssessmentService {

	private final UserProfileRepository profiles;
	private final AnonymousAssessmentSessionRepository sessions;
	private final QuestionnaireDefinitionRepository definitions;
	private final QuestionnaireQuestionRepository questions;
	private final QuestionnaireScoreBandRepository scoreBands;
	private final AssessmentSubmissionRepository submissions;
	private final AssessmentAnswerRepository answers;
	private final AssessmentResultRepository results;
	private final OutboxEventRepository outbox;
	private final AssessmentScoringPolicy scoring;
	private final AssessmentProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();
	private final ConsentService consents;
	private final PrivacyDisclosureService disclosures;

	public AssessmentService(UserProfileRepository profiles, AnonymousAssessmentSessionRepository sessions,
			QuestionnaireDefinitionRepository definitions, QuestionnaireQuestionRepository questions,
			QuestionnaireScoreBandRepository scoreBands, AssessmentSubmissionRepository submissions,
			AssessmentAnswerRepository answers, AssessmentResultRepository results, OutboxEventRepository outbox,
			AssessmentScoringPolicy scoring, AssessmentProperties properties, ObjectMapper objectMapper, Clock clock,
			ConsentService consents, PrivacyDisclosureService disclosures) {
		this.profiles = profiles;
		this.sessions = sessions;
		this.definitions = definitions;
		this.questions = questions;
		this.scoreBands = scoreBands;
		this.submissions = submissions;
		this.answers = answers;
		this.results = results;
		this.outbox = outbox;
		this.scoring = scoring;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.consents = consents;
		this.disclosures = disclosures;
	}

	@Transactional
	public AnonymousSessionView createAnonymousSession() {
		var token = token();
		var createdAt = clock.instant();
		var session = sessions.save(new AnonymousAssessmentSessionEntity(hash(token), createdAt,
				createdAt.plus(properties.anonymousSessionTtl())));
		return new AnonymousSessionView(session.id(), token, session.expiresAt());
	}

	@Transactional
	public AssessmentView submitAuthenticated(UUID userId, String idempotencyKey, UUID correlationId,
			SubmissionCommand command) {
		profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"PROFILE_NOT_FOUND", "Care profile was not found"));
		consents.requireGranted(userId, command.privacyPolicyVersion());
		disclosures.requireCurrent(command.privacyPolicyVersion(), command.privacyDisclosureAcknowledged());
		var requestHash = requestHash(command);
		var existing = submissions.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
		if (existing.isPresent()) {
			return replay(existing.orElseThrow(), requestHash, false);
		}
		return submit(command, idempotencyKey, requestHash, correlationId, userId, null, null);
	}

	@Transactional
	public AssessmentView submitAnonymous(UUID sessionId, String sessionToken, String idempotencyKey,
			UUID correlationId, SubmissionCommand command) {
		var session = authenticatedSession(sessionId, sessionToken);
		disclosures.requireCurrent(command.privacyPolicyVersion(), command.privacyDisclosureAcknowledged());
		var requestHash = requestHash(command);
		var existing = submissions.findByAnonymousSessionIdAndIdempotencyKey(sessionId, idempotencyKey);
		if (existing.isPresent()) {
			return replay(existing.orElseThrow(), requestHash, true);
		}
		return submit(command, idempotencyKey, requestHash, correlationId, null, sessionId, session.expiresAt());
	}

	@Transactional(readOnly = true)
	public AssessmentView getAuthenticated(UUID userId, UUID assessmentId) {
		var submission = submissions.findByIdAndUserId(assessmentId, userId).orElseThrow(
				() -> new ApiException(HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND", "Assessment was not found"));
		return view(submission, false);
	}

	@Transactional
	public AssessmentView getAnonymous(UUID sessionId, String sessionToken, UUID assessmentId) {
		var session = authenticatedSession(sessionId, sessionToken);
		var submission = submissions.findByIdAndAnonymousSessionId(assessmentId, sessionId).orElseThrow(
				() -> new ApiException(HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND", "Assessment was not found"));
		submission.extendRetention(session.expiresAt());
		return view(submission, true);
	}

	@Transactional(readOnly = true)
	public HistoryPage history(UUID userId, String cursor, int limit) {
		var decoded = decodeCursor(cursor);
		var pageable = PageRequest.of(0, limit + 1);
		var page = decoded == null
				? submissions.findByUserIdAndVoidedAtIsNullOrderBySubmittedAtDescIdDesc(userId, pageable)
				: submissions.findHistoryAfter(userId, decoded.submittedAt(), decoded.assessmentId(), pageable);
		var hasMore = page.size() > limit;
		var selected = hasMore ? page.subList(0, limit) : page;
		var items = selected.stream().map(submission -> view(submission, false)).toList();
		var nextCursor = hasMore ? encodeCursor(selected.getLast()) : null;
		return new HistoryPage(items, nextCursor, hasMore);
	}

	private AssessmentView submit(SubmissionCommand command, String idempotencyKey, String requestHash,
			UUID correlationId, UUID userId, UUID sessionId, Instant retentionExpiresAt) {
		var definition = definitionForSubmission(command.questionnaireDefinitionId());
		var definitionQuestions = questions.findByDefinitionIdOrderByItemNumber(definition.id());
		var validatedAnswers = validateAnswers(command.answers(), definition, definitionQuestions);
		var bands = scoreBands.findByDefinitionIdOrderByOrdinal(definition.id()).stream()
				.map(band -> new AssessmentScoringPolicy.ScoreBand(band.code(), band.minimumScore(), band.maximumScore()))
				.toList();
		var safetyItemNumbers = definitionQuestions.stream().filter(QuestionnaireQuestionEntity::safetyItem)
				.map(question -> (int) question.itemNumber()).toList();
		var scored = scoring.score(definition.instrument(), validatedAnswers.scoringAnswers(), bands, safetyItemNumbers);
		var submittedAt = clock.instant();
		var submission = userId == null
				? AssessmentSubmissionEntity.anonymous(sessionId, definition.id(), idempotencyKey, requestHash,
						command.privacyPolicyVersion(), submittedAt, retentionExpiresAt)
				: AssessmentSubmissionEntity.authenticated(userId, definition.id(), idempotencyKey, requestHash,
						command.privacyPolicyVersion(), submittedAt);
		submission = submissions.saveAndFlush(submission);
		var submissionId = submission.id();
		answers.saveAll(validatedAnswers.entities().stream()
				.map(answer -> new AssessmentAnswerEntity(submissionId, definition.id(), answer.questionId(),
						answer.value()))
				.toList());
		var safetyPolicyVersion = "PHQ9".equals(definition.instrument()) ? properties.phq9SafetyPolicyVersion() : null;
		var result = results.save(new AssessmentResultEntity(submissionId, scored, definition.scoringVersion(),
				safetyPolicyVersion, submittedAt));
		var payload = objectMapper.createObjectNode();
		payload.put("assessmentId", submissionId.toString());
		payload.put("ownerType", userId == null ? "ANONYMOUS" : "AUTHENTICATED_USER");
		if (userId != null) {
			payload.put("userId", userId.toString());
		}
		payload.put("definitionId", definition.id().toString());
		payload.put("instrument", definition.instrument());
		payload.put("questionnaireVersion", definition.version());
		payload.put("scoringVersion", definition.scoringVersion());
		payload.put("totalScore", scored.totalScore());
		payload.put("screeningLevel", scored.screeningLevel().name());
		payload.put("safetyStatus", scored.safetyStatus().name());
		if (safetyPolicyVersion == null) {
			payload.putNull("safetyPolicyVersion");
		}
		else {
			payload.put("safetyPolicyVersion", safetyPolicyVersion);
		}
		payload.put("completedAt", submittedAt.toString());
		outbox.save(new OutboxEventEntity(submissionId, correlationId, payload, submittedAt));
		return view(submission, definition, result, userId == null);
	}

	private AssessmentView replay(AssessmentSubmissionEntity submission, String requestHash, boolean anonymous) {
		if (!MessageDigest.isEqual(submission.requestHash().getBytes(StandardCharsets.US_ASCII),
				requestHash.getBytes(StandardCharsets.US_ASCII))) {
			throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
					"Idempotency key was reused with different assessment answers");
		}
		return view(submission, anonymous);
	}

	private AssessmentView view(AssessmentSubmissionEntity submission, boolean anonymous) {
		var definition = definitions.findById(submission.definitionId()).orElseThrow(
				() -> new ApiException(HttpStatus.NOT_FOUND, "QUESTIONNAIRE_NOT_FOUND", "Questionnaire was not found"));
		var result = results.findById(submission.id()).orElseThrow(
				() -> new ApiException(HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND", "Assessment result was not found"));
		return view(submission, definition, result, anonymous);
	}

	private AssessmentView view(AssessmentSubmissionEntity submission, QuestionnaireDefinitionEntity definition,
			AssessmentResultEntity result, boolean anonymous) {
		return new AssessmentView(submission.id(), definition.id(), definition.instrument(), definition.version(),
				submission.privacyPolicyVersion(),
				submission.submittedAt(), submission.voidedAt(), new ResultView(result.totalScore(),
						result.screeningLevel(), result.scoringVersion(), result.safetyStatus(),
						result.safetyPolicyVersion(), result.disclaimerCode()),
				anonymous ? submission.retentionExpiresAt() : null);
	}

	private QuestionnaireDefinitionEntity definitionForSubmission(UUID definitionId) {
		var definition = definitions.findById(definitionId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"QUESTIONNAIRE_NOT_FOUND", "Questionnaire was not found"));
		if (!definition.published()
				|| !("PHQ9".equals(definition.instrument()) || "GAD7".equals(definition.instrument()))) {
			throw new ApiException(HttpStatus.CONFLICT, "QUESTIONNAIRE_VERSION_UNAVAILABLE",
					"Questionnaire version is not available for submission");
		}
		return definition;
	}

	private ValidatedAnswers validateAnswers(List<AnswerCommand> supplied,
			QuestionnaireDefinitionEntity definition, List<QuestionnaireQuestionEntity> definitionQuestions) {
		var suppliedById = new HashMap<UUID, Integer>();
		var duplicate = new HashSet<UUID>();
		for (var answer : supplied) {
			if (suppliedById.putIfAbsent(answer.questionId(), answer.value()) != null) {
				duplicate.add(answer.questionId());
			}
		}
		var expectedIds = definitionQuestions.stream().map(QuestionnaireQuestionEntity::id).collect(java.util.stream.Collectors.toSet());
		var unknown = suppliedById.keySet().stream().filter(id -> !expectedIds.contains(id)).toList();
		var missing = expectedIds.stream().filter(id -> !suppliedById.containsKey(id)).toList();
		if (supplied.size() != definition.expectedQuestionCount() || !duplicate.isEmpty() || !unknown.isEmpty()
				|| !missing.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Questionnaire answers are incomplete",
					List.of(new FieldViolation("answers", "INCOMPLETE_QUESTIONNAIRE",
							"One valid answer is required for every questionnaire question")));
		}
		var persisted = definitionQuestions.stream()
				.map(question -> new ValidatedAnswer(question.id(), question.itemNumber(), suppliedById.get(question.id())))
				.toList();
		var scoringAnswers = persisted.stream()
				.map(answer -> new AssessmentScoringPolicy.QuestionAnswer(answer.itemNumber(), answer.value())).toList();
		return new ValidatedAnswers(persisted, scoringAnswers);
	}

	private AnonymousAssessmentSessionEntity authenticatedSession(UUID sessionId, String token) {
		if (token.length() < 43 || token.length() > 512) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ANONYMOUS_SESSION",
					"Anonymous assessment session is invalid");
		}
		var session = sessions.findByIdForUpdate(sessionId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
				"INVALID_ANONYMOUS_SESSION", "Anonymous assessment session is invalid"));
		if (!MessageDigest.isEqual(session.tokenHash().getBytes(StandardCharsets.US_ASCII),
				hash(token).getBytes(StandardCharsets.US_ASCII))) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ANONYMOUS_SESSION",
					"Anonymous assessment session is invalid");
		}
		if (session.unavailableAt(clock.instant())) {
			throw new ApiException(HttpStatus.GONE, "ANONYMOUS_SESSION_EXPIRED",
					"Anonymous assessment session has expired");
		}
		session.recordActivity(clock.instant(), properties.anonymousSessionTtl(), properties.anonymousSessionMaximumLifetime());
		return session;
	}

	private String requestHash(SubmissionCommand command) {
		var canonical = new StringBuilder(command.questionnaireDefinitionId().toString())
				.append('|').append(command.privacyPolicyVersion()).append('|')
				.append(command.privacyDisclosureAcknowledged());
		command.answers().stream().sorted(java.util.Comparator.comparing(AnswerCommand::questionId)
				.thenComparingInt(AnswerCommand::value))
				.forEach(answer -> canonical.append('|').append(answer.questionId()).append(':').append(answer.value()));
		return hmac(canonical.toString());
	}

	private String hmac(String value) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(properties.idempotencyHmacKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException exception) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
		}
	}

	private String token() {
		var bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private Cursor decodeCursor(String cursor) {
		if (cursor == null) return null;
		try {
			var decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
			if (decoded.length != 2) throw new IllegalArgumentException();
			return new Cursor(Instant.parse(decoded[0]), UUID.fromString(decoded[1]));
		}
		catch (RuntimeException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Assessment history cursor is invalid");
		}
	}

	private String encodeCursor(AssessmentSubmissionEntity submission) {
		var value = submission.submittedAt() + "|" + submission.id();
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	public record SubmissionCommand(UUID questionnaireDefinitionId, String privacyPolicyVersion,
			boolean privacyDisclosureAcknowledged, List<AnswerCommand> answers) {
		public SubmissionCommand {
			answers = List.copyOf(answers);
		}
	}

	public record AnswerCommand(UUID questionId, int value) {
	}

	public record AnonymousSessionView(UUID sessionId, String sessionToken, Instant expiresAt) {

		@Override
		public String toString() {
			return "AnonymousSessionView[sessionId=" + sessionId + ", sessionToken=[REDACTED], expiresAt=" + expiresAt
					+ "]";
		}
	}

	public record AssessmentView(UUID assessmentId, UUID questionnaireDefinitionId, String instrument,
			String questionnaireVersion, String privacyPolicyVersion, Instant submittedAt, Instant voidedAt,
			ResultView result, Instant expiresAt) {
	}

	public record HistoryPage(List<AssessmentView> items, String nextCursor, boolean hasMore) {
	}

	public record ResultView(int totalScore, ScreeningLevel screeningLevel, String scoringVersion,
			SafetyStatus safetyStatus, String safetyPolicyVersion, String disclaimerCode) {
	}

	private record ValidatedAnswer(UUID questionId, int itemNumber, int value) {
	}

	private record ValidatedAnswers(List<ValidatedAnswer> entities,
			List<AssessmentScoringPolicy.QuestionAnswer> scoringAnswers) {
	}

	private record Cursor(Instant submittedAt, UUID assessmentId) {
	}
}
