package com.mentalbridge.care.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
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
import com.mentalbridge.care.support.DomainAwareSupportPolicy.DomainResolution;
import com.mentalbridge.care.support.SupportEvaluationV2Repository.Evidence;

@Service
public class SupportEvaluationV2Service {

	private static final int EVALUATION_VERSION = 2;
	private static final String SAFETY_INSTRUMENT = "PHQ9";
	private static final String SAFETY_TRIGGER = "PHQ9_ITEM_9";

	private final SupportEvaluationV2Repository evaluations;
	private final SupportEvaluationV2DomainRepository domains;
	private final SupportEvaluationV2SafetyRepository safety;
	private final UserProfileRepository profiles;
	private final DomainAwareSupportPolicy policy;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public SupportEvaluationV2Service(SupportEvaluationV2Repository evaluations,
			SupportEvaluationV2DomainRepository domains, SupportEvaluationV2SafetyRepository safety,
			UserProfileRepository profiles, DomainAwareSupportPolicy policy, ObjectMapper objectMapper, Clock clock) {
		this.evaluations = evaluations;
		this.domains = domains;
		this.safety = safety;
		this.profiles = profiles;
		this.policy = policy;
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
		var replay = evaluations.findRequest(userId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.orElseThrow().getRequestHash().equals(requestHash)) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"Idempotency-Key was already used with different screening evidence");
			}
			return view(requiredOwnedEvaluation(userId, replay.orElseThrow().getEvaluationId()));
		}

		var policyVersion = evaluations.currentPolicyVersion().orElseThrow(() -> new ApiException(
				HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_POLICY_UNAVAILABLE",
				"No published domain-aware support policy is available"));
		var existing = evaluations.findByUserIdAndPhq9AssessmentIdAndGad7AssessmentIdAndPolicyVersion(userId,
				command.phq9AssessmentId(), command.gad7AssessmentId(), policyVersion);
		if (existing.isPresent()) {
			evaluations.insertRequest(userId, idempotencyKey, requestHash, existing.orElseThrow().id(), clock.instant());
			return view(existing.orElseThrow());
		}

		var phq9 = requiredEvidence(userId, command.phq9AssessmentId(), "PHQ9",
				ScreeningDomain.DEPRESSIVE_SYMPTOMS, policyVersion);
		var gad7 = requiredEvidence(userId, command.gad7AssessmentId(), "GAD7",
				ScreeningDomain.ANXIETY_SYMPTOMS, policyVersion);
		validateSafetyEvidence(phq9, gad7);

		var evaluatedAt = clock.instant();
		var evaluation = new SupportEvaluationV2Entity(UUID.randomUUID(), userId, phq9.assessmentId(),
				gad7.assessmentId(), policyVersion, evaluatedAt);
		evaluations.saveAndFlush(evaluation);
		var contributions = List.of(domain(evaluation.id(), (short) 1, phq9, policy.resolve("PHQ9", phq9.screeningLevel())),
				domain(evaluation.id(), (short) 2, gad7, policy.resolve("GAD7", gad7.screeningLevel())));
		domains.saveAllAndFlush(contributions);
		var safetyEvidence = new SupportEvaluationV2SafetyEntity(evaluation.id(), phq9.assessmentId(),
				SAFETY_INSTRUMENT, SAFETY_TRIGGER, phq9.safetyStatus(), phq9.safetyPolicyVersion(),
				policy.safetyReason(phq9.safetyStatus()));
		safety.saveAndFlush(safetyEvidence);
		evaluations.insertRequest(userId, idempotencyKey, requestHash, evaluation.id(), evaluatedAt);
		var result = view(evaluation, contributions, safetyEvidence);
		evaluations.appendOutbox(evaluation, correlationId, eventPayload(evaluation, result));
		return result;
	}

	@Transactional(readOnly = true)
	public EvaluationView get(UUID userId, UUID evaluationId) {
		return view(requiredOwnedEvaluation(userId, evaluationId));
	}

	@Transactional(readOnly = true)
	public EvaluationView getCurrentCompatible(UUID userId, UUID evaluationId) {
		var evaluation = requiredOwnedEvaluation(userId, evaluationId);
		var currentPolicy = evaluations.currentPolicyVersion().orElseThrow(() -> new ApiException(
				HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_POLICY_UNAVAILABLE",
				"No published domain-aware support policy is available"));
		if (!currentPolicy.equals(evaluation.policyVersion())) {
			throw stale("Support evaluation policy is no longer current");
		}
		try {
			requiredEvidence(userId, evaluation.phq9AssessmentId(), "PHQ9",
					ScreeningDomain.DEPRESSIVE_SYMPTOMS, currentPolicy);
			requiredEvidence(userId, evaluation.gad7AssessmentId(), "GAD7",
					ScreeningDomain.ANXIETY_SYMPTOMS, currentPolicy);
		}
		catch (ApiException exception) {
			if ("ASSESSMENT_NOT_FOUND".equals(exception.code()) || "SUPPORT_EVIDENCE_INCOMPATIBLE".equals(exception.code())) {
				throw stale("Support evaluation source evidence is no longer current");
			}
			throw exception;
		}
		return view(evaluation);
	}

	private SupportEvaluationV2Entity requiredOwnedEvaluation(UUID userId, UUID evaluationId) {
		return evaluations.findByIdAndUserId(evaluationId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_EVALUATION_NOT_FOUND", "Support evaluation was not found"));
	}

	private Evidence requiredEvidence(UUID userId, UUID assessmentId, String expectedInstrument,
			ScreeningDomain expectedDomain, String policyVersion) {
		var evidence = evaluations.findEvidence(userId, assessmentId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "ASSESSMENT_NOT_FOUND",
				"Screening evidence was not found for the authenticated user"));
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
		if (!evaluations.isEligible(policyVersion, evidence, expectedDomain)) {
			throw incompatible("Screening evidence is not compatible with the published domain-aware policy");
		}
		return evidence;
	}

	private void validateSafetyEvidence(Evidence phq9, Evidence gad7) {
		if (phq9.safetyStatus() == SafetyStatus.NOT_APPLICABLE || phq9.safetyPolicyVersion() == null) {
			throw incompatible("PHQ-9 safety evidence is incomplete");
		}
		if (gad7.safetyStatus() != SafetyStatus.NOT_APPLICABLE || gad7.safetyPolicyVersion() != null) {
			throw incompatible("GAD-7 must not be treated as safety evidence");
		}
	}

	private SupportEvaluationV2DomainEntity domain(UUID evaluationId, short ordinal, Evidence evidence,
			DomainResolution resolution) {
		return new SupportEvaluationV2DomainEntity(UUID.randomUUID(), evaluationId, ordinal, evidence.assessmentId(),
				evidence.definitionId(), evidence.instrument(), resolution.domain(), evidence.questionnaireVersion(),
				evidence.scoringVersion(), evidence.screeningLevel(), resolution.pathway(), resolution.reasonCode());
	}

	private EvaluationView view(SupportEvaluationV2Entity evaluation) {
		var contributions = domains.findBySupportEvaluationIdOrderByOrdinal(evaluation.id());
		if (contributions.size() != 2) {
			throw new IllegalStateException("Stored domain-aware support evidence is incomplete");
		}
		var safetyEvidence = safety.findById(evaluation.id())
				.orElseThrow(() -> new IllegalStateException("Stored support safety evidence is unavailable"));
		return view(evaluation, contributions, safetyEvidence);
	}

	private EvaluationView view(SupportEvaluationV2Entity evaluation,
			List<SupportEvaluationV2DomainEntity> contributions, SupportEvaluationV2SafetyEntity safetyEvidence) {
		return new EvaluationView(evaluation.id(), EVALUATION_VERSION, evaluation.policyVersion(), evaluation.evaluatedAt(),
				contributions.stream().map(this::domainView).toList(),
				new SafetyEvidenceView(safetyEvidence.sourceAssessmentId(), safetyEvidence.instrument(),
						safetyEvidence.triggerCode(), safetyEvidence.safetyStatus(),
						safetyEvidence.safetyPolicyVersion(), safetyEvidence.reasonCode()),
				SupportEvaluationService.DISCLAIMER_CODE, SupportEvaluationService.DISCLAIMER_TEXT);
	}

	private DomainContributionView domainView(SupportEvaluationV2DomainEntity contribution) {
		return new DomainContributionView(contribution.assessmentId(), contribution.definitionId(),
				contribution.instrument(), contribution.domain(), contribution.questionnaireVersion(),
				contribution.scoringVersion(), contribution.screeningLevel(), contribution.supportPathway(),
				List.of(contribution.reasonCode()));
	}

	private String eventPayload(SupportEvaluationV2Entity evaluation, EvaluationView result) {
		try {
			var eventDomains = result.contributingDomains().stream()
					.map(domain -> new EventDomainContribution(domain.assessmentId(), domain.questionnaireDefinitionId(),
							domain.instrument(), domain.domain(), domain.questionnaireVersion(), domain.scoringVersion(),
							domain.screeningLevel(), domain.supportPathway(), domain.reasonCodes()))
					.toList();
			return objectMapper.writeValueAsString(new SupportEvaluationCreatedEvent(evaluation.id(), evaluation.userId(),
					EVALUATION_VERSION, evaluation.policyVersion(), evaluation.evaluatedAt(), eventDomains,
					result.safetyEvidence()));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Support evaluation event could not be serialized", exception);
		}
	}

	private String hash(EvaluationCommand command) {
		try {
			var canonical = "version=2\nphq9=" + command.phq9AssessmentId() + "\ngad7=" + command.gad7AssessmentId();
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

	private ApiException stale(String message) {
		return new ApiException(HttpStatus.CONFLICT, "SUPPORT_EVALUATION_STALE", message);
	}

	private ApiException validation(String field, String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Support evidence is invalid",
				List.of(new ApiException.FieldViolation(field, code, message)));
	}

	public record EvaluationCommand(UUID phq9AssessmentId, UUID gad7AssessmentId) { }
	public record EvaluationView(UUID supportEvaluationId, int evaluationVersion, String policyVersion,
			Instant evaluatedAt, List<DomainContributionView> contributingDomains,
			SafetyEvidenceView safetyEvidence, String disclaimerCode, String disclaimer) { }
	public record DomainContributionView(UUID assessmentId, UUID questionnaireDefinitionId, String instrument,
			ScreeningDomain domain, String questionnaireVersion, String scoringVersion,
			com.mentalbridge.care.assessment.ScreeningLevel screeningLevel, DomainSupportPathway supportPathway,
			List<DomainReasonCode> reasonCodes) { }
	public record SafetyEvidenceView(UUID sourceAssessmentId, String instrument, String trigger,
			SafetyStatus status, String policyVersion, SafetyReasonCode reasonCode) { }
	private record EventDomainContribution(UUID assessmentId, UUID questionnaireDefinitionId, String instrument,
			ScreeningDomain domain, String questionnaireVersion, String scoringVersion,
			com.mentalbridge.care.assessment.ScreeningLevel screeningLevel, DomainSupportPathway supportPathway,
			List<DomainReasonCode> reasonCodes) { }
	private record SupportEvaluationCreatedEvent(UUID supportEvaluationId, UUID userId, int evaluationVersion,
			String policyVersion, Instant evaluatedAt, List<EventDomainContribution> contributingDomains,
			SafetyEvidenceView safetyEvidence) { }
}
