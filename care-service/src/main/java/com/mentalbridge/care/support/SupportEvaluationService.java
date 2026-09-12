package com.mentalbridge.care.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationRepository.Evidence;
import com.mentalbridge.care.support.SupportEvaluationRepository.Guidance;

@Service
public class SupportEvaluationService {

	static final String DISCLAIMER_CODE = "SCREENING_NOT_DIAGNOSIS";
	static final String DISCLAIMER_TEXT = "Đây là kết quả sàng lọc triệu chứng, không phải chẩn đoán y khoa. "
			+ "MentalBridge không cung cấp dịch vụ ứng cứu khẩn cấp, không giám sát con người 24/7 "
			+ "và không tự động liên hệ bên thứ ba.";
	public static final String SAFETY_FALLBACK = "Nếu bạn cảm thấy mình không an toàn hoặc có nguy cơ gây hại cho bản thân, "
			+ "hãy chủ động liên hệ dịch vụ khẩn cấp hoặc cơ sở y tế phù hợp tại khu vực của bạn.";

	private final SupportEvaluationRepository repository;
	private final UserProfileRepository profiles;
	private final SupportRoutingPolicy routingPolicy;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public SupportEvaluationService(SupportEvaluationRepository repository, UserProfileRepository profiles,
			SupportRoutingPolicy routingPolicy, ObjectMapper objectMapper, Clock clock) {
		this.repository = repository;
		this.profiles = profiles;
		this.routingPolicy = routingPolicy;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional
	public EvaluationView evaluate(UUID userId, String idempotencyKey, UUID correlationId, EvaluationCommand command) {
		if (command.phq9AssessmentId().equals(command.gad7AssessmentId())) {
			throw validation("gad7AssessmentId", "DUPLICATE_EVIDENCE",
					"PHQ-9 and GAD-7 evidence must be different assessments");
		}
		if (profiles.findByIdForUpdate(userId).isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Care profile was not found");
		}
		var requestHash = hash(command);
		var replay = repository.findRequest(userId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.orElseThrow().getRequestHash().equals(requestHash)) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"Idempotency-Key was already used with different screening evidence");
			}
			return view(repository.findByIdAndUserId(replay.orElseThrow().getEvaluationId(), userId)
					.orElseThrow(() -> new IllegalStateException("Stored support evaluation is unavailable")));
		}

		var policyVersion = repository.currentPolicyVersion().orElseThrow(() -> new ApiException(
				HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_POLICY_UNAVAILABLE", "No published support policy is available"));
		var existing = repository.findByUserIdAndPhq9AssessmentIdAndGad7AssessmentIdAndPolicyVersion(userId,
				command.phq9AssessmentId(), command.gad7AssessmentId(), policyVersion);
		if (existing.isPresent()) {
			repository.insertRequest(userId, idempotencyKey, requestHash, existing.orElseThrow().id(), clock.instant());
			return view(existing.orElseThrow());
		}

		var phq9 = requiredEvidence(userId, command.phq9AssessmentId(), "PHQ9", policyVersion);
		var gad7 = requiredEvidence(userId, command.gad7AssessmentId(), "GAD7", policyVersion);
		validateSafetyMetadata(phq9, gad7);

		var resolution = routingPolicy.resolve(phq9.screeningLevel(), phq9.safetyStatus(), gad7.screeningLevel());
		var evaluatedAt = clock.instant();
		var reasons = resolution.reasons();
		var evaluation = new SupportEvaluationEntity(UUID.randomUUID(), userId, phq9.assessmentId(), gad7.assessmentId(),
				policyVersion, resolution.tier(), reasons.getFirst(), reasons.size() == 2 ? reasons.get(1) : null,
				evaluatedAt);
		repository.saveAndFlush(evaluation);
		repository.insertRequest(userId, idempotencyKey, requestHash, evaluation.id(), evaluatedAt);
		repository.appendOutbox(evaluation, correlationId, eventPayload(evaluation, reasons));
		return view(evaluation, phq9, gad7);
	}

	@Transactional(readOnly = true)
	public EvaluationView get(UUID userId, UUID evaluationId) {
		return view(repository.findByIdAndUserId(evaluationId, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"SUPPORT_EVALUATION_NOT_FOUND", "Support evaluation was not found")));
	}

	private Evidence requiredEvidence(UUID userId, UUID assessmentId, String expectedInstrument, String policyVersion) {
		var evidence = repository.findEvidence(userId, assessmentId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"ASSESSMENT_NOT_FOUND", "Screening evidence was not found for the authenticated user"));
		if (!expectedInstrument.equals(evidence.instrument())) {
			throw incompatible("Screening evidence has the wrong instrument");
		}
		if ("GAD7".equals(expectedInstrument)
				&& evidence.screeningLevel() == com.mentalbridge.care.assessment.ScreeningLevel.MODERATELY_SEVERE) {
			throw incompatible("GAD-7 evidence has an unsupported screening level");
		}
		if (evidence.voidedAt() != null || evidence.screeningLevel() == null || evidence.scoringVersion() == null
				|| evidence.safetyStatus() == null) {
			throw incompatible("Screening evidence is voided or incomplete");
		}
		if (!repository.isEligible(policyVersion, evidence)) {
			throw incompatible("Screening evidence is not compatible with the published support policy");
		}
		return evidence;
	}

	private void validateSafetyMetadata(Evidence phq9, Evidence gad7) {
		if (phq9.safetyStatus() == SafetyStatus.NOT_APPLICABLE || phq9.safetyPolicyVersion() == null) {
			throw incompatible("PHQ-9 safety evidence is incomplete");
		}
		if (gad7.safetyStatus() != SafetyStatus.NOT_APPLICABLE || gad7.safetyPolicyVersion() != null) {
			throw incompatible("GAD-7 must not be treated as safety evidence");
		}
	}

	private EvaluationView view(SupportEvaluationEntity evaluation) {
		var phq9 = repository.findEvidence(evaluation.userId(), evaluation.phq9AssessmentId())
				.orElseThrow(() -> incompatible("Stored PHQ-9 evidence is unavailable"));
		var gad7 = repository.findEvidence(evaluation.userId(), evaluation.gad7AssessmentId())
				.orElseThrow(() -> incompatible("Stored GAD-7 evidence is unavailable"));
		return view(evaluation, phq9, gad7);
	}

	private EvaluationView view(SupportEvaluationEntity evaluation, Evidence phq9, Evidence gad7) {
		var reasons = new ArrayList<SupportReasonCode>(2);
		reasons.add(evaluation.primaryReasonCode());
		if (evaluation.secondaryReasonCode() != null) reasons.add(evaluation.secondaryReasonCode());
		var guidance = repository.guidance(evaluation.policyVersion(), evaluation.supportTier())
				.orElseGet(() -> fallbackGuidance(evaluation.supportTier()));
		return new EvaluationView(evaluation.id(), evaluation.policyVersion(), evaluation.evaluatedAt(),
				evaluation.supportTier(),
				List.copyOf(reasons), List.of(evidenceView(evaluation.policyVersion(), phq9),
						evidenceView(evaluation.policyVersion(), gad7)),
				new NextStep(guidance.code(), guidance.contentVersion(), guidance.text(), guidance.boundary()),
				guidance.safetyGuidance(), DISCLAIMER_CODE, DISCLAIMER_TEXT);
	}

	private EvidenceView evidenceView(String policyVersion, Evidence evidence) {
		var meaning = repository.meaning(policyVersion, evidence.instrument(), evidence.screeningLevel());
		return new EvidenceView(evidence.assessmentId(), evidence.instrument(), evidence.questionnaireVersion(),
				evidence.scoringVersion(), evidence.screeningLevel(), evidence.safetyStatus(),
				new Meaning(meaning.code(), meaning.contentVersion(), meaning.referencePeriodDays(), meaning.text(),
						meaning.limitation()));
	}

	private Guidance fallbackGuidance(SupportTier tier) {
		if (tier != SupportTier.SAFETY_FOLLOW_UP_RECOMMENDED) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_GUIDANCE_UNAVAILABLE",
					"Reviewed support guidance is unavailable");
		}
		return new Guidance("REVIEW_SAFETY_GUIDANCE", "mb-safety-guidance-vi-vn-v1",
				"Ưu tiên xem hướng dẫn an toàn ngay bên dưới và chủ động tìm hỗ trợ trực tiếp nếu bạn cảm thấy không an toàn.",
				"MentalBridge không tự động liên hệ người khác, đặt lịch hoặc chia sẻ dữ liệu.", SAFETY_FALLBACK);
	}

	private String eventPayload(SupportEvaluationEntity evaluation, List<SupportReasonCode> reasons) {
		try {
			return objectMapper.writeValueAsString(new SupportTierResolvedEvent(evaluation.id(), evaluation.userId(),
					evaluation.policyVersion(), evaluation.supportTier(), reasons, evaluation.phq9AssessmentId(),
					evaluation.gad7AssessmentId(), evaluation.evaluatedAt()));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Support event could not be serialized", exception);
		}
	}

	private String hash(EvaluationCommand command) {
		try {
			var canonical = "phq9=" + command.phq9AssessmentId() + "\ngad7=" + command.gad7AssessmentId();
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ApiException incompatible(String message) {
		return new ApiException(HttpStatus.CONFLICT, "SUPPORT_EVIDENCE_INCOMPATIBLE", message);
	}

	private ApiException validation(String field, String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Support evidence is invalid",
				List.of(new ApiException.FieldViolation(field, code, message)));
	}

	public record EvaluationCommand(UUID phq9AssessmentId, UUID gad7AssessmentId) { }
	public record EvaluationView(UUID supportEvaluationId, String policyVersion, Instant evaluatedAt,
			SupportTier supportTier, List<SupportReasonCode> reasonCodes, List<EvidenceView> evidence,
			NextStep nextStep, String safetyGuidance, String disclaimerCode, String disclaimer) { }
	public record EvidenceView(UUID assessmentId, String instrument, String questionnaireVersion, String scoringVersion,
			com.mentalbridge.care.assessment.ScreeningLevel screeningLevel, SafetyStatus safetyStatus, Meaning meaning) { }
	public record Meaning(String meaningCode, String contentVersion, int referencePeriodDays, String text,
			String limitation) { }
	public record NextStep(String code, String contentVersion, String text, String boundary) { }
	private record SupportTierResolvedEvent(UUID supportEvaluationId, UUID userId, String policyVersion,
			SupportTier supportTier, List<SupportReasonCode> reasonCodes, UUID phq9AssessmentId,
			UUID gad7AssessmentId, Instant evaluatedAt) { }
}
