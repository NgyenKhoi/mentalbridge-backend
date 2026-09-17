package com.mentalbridge.care.supportguide;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.RequiredEligibilityRole;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityOutcome;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityQuery;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityResult;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningInstrument;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.SupportTier;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationService;
import com.mentalbridge.care.support.SupportEvaluationV2Service;
import com.mentalbridge.care.support.SupportEvaluationV2Service.DomainContributionView;
import com.mentalbridge.care.support.SupportEvaluationV2Service.EvaluationCommand;
import com.mentalbridge.care.support.SupportEvaluationV2Service.EvaluationView;
import com.mentalbridge.care.supportguide.SupportGuideWriter.Draft;
import com.mentalbridge.care.supportguide.SupportGuideWriter.ResourceDraft;
import com.mentalbridge.care.supportguide.SupportGuideWriter.StoredGuide;

@Service
public class SupportGuideService {

	static final String GUIDE_POLICY_VERSION = "mb-support-guide-capstone-v1";
	private static final String EXPLANATION = "Hướng dẫn này tóm tắt kết quả sàng lọc đã lưu và gợi ý tài nguyên tự hỗ trợ đã được rà soát. Đây không phải chẩn đoán hoặc kế hoạch điều trị.";
	private static final String STANDARD_SAFETY = "Nếu tình trạng của bạn thay đổi hoặc bạn cảm thấy không an toàn, hãy chủ động tìm hỗ trợ trực tiếp phù hợp tại khu vực của bạn.";
	private static final List<Candidate> CANDIDATES = List.of(
			new Candidate("00000000-0000-4000-8000-000000000102", "0", "DEPRESSIVE_SYMPTOMS"),
			new Candidate("00000000-0000-4000-8000-000000000104", "0", "DEPRESSIVE_SYMPTOMS"),
			new Candidate("00000000-0000-4000-8000-000000000103", "0", "ANXIETY_SYMPTOMS"),
			new Candidate("00000000-0000-4000-8000-000000000101", "0", "ANXIETY_SYMPTOMS"));

	private final SupportEvaluationV2Service evaluations;
	private final ResourceEligibilityClient eligibility;
	private final SupportGuideWriter writer;
	private final Clock clock;

	public SupportGuideService(SupportEvaluationV2Service evaluations, ResourceEligibilityClient eligibility,
			SupportGuideWriter writer, Clock clock) {
		this.evaluations = evaluations;
		this.eligibility = eligibility;
		this.writer = writer;
		this.clock = clock;
	}

	public SupportGuideView generate(UUID userId, String bearerToken, String idempotencyKey,
			UUID correlationId, GenerateCommand command) {
		String requestHash = hash(command);
		var replay = writer.request(userId, idempotencyKey);
		if (replay != null) {
			if (!replay.getRequestHash().equals(requestHash)) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"Idempotency-Key was already used with different screening evidence");
			}
			return view(userId, writer.required(userId, replay.getGuideId()));
		}

		EvaluationView evaluation = evaluations.evaluate(userId, "support-guide-evaluation:" + requestHash,
				correlationId, new EvaluationCommand(command.phq9AssessmentId(), command.gad7AssessmentId()));
		var batch = request(evaluation);
		var resolved = eligibility.resolve(batch, bearerToken, correlationId);
		var selected = selected(batch, resolved.results());
		String resourceStatus = resourceStatus(selected.size(), resolved.results());
		var safety = evaluation.safetyEvidence();
		boolean positive = safety.status() == SafetyStatus.POSITIVE_SAFETY_SCREEN;
		var stored = writer.persist(userId, idempotencyKey, requestHash,
				new Draft(evaluation.supportEvaluationId(), clock.instant(), "STANDARD_POST_SCREENING_GUIDANCE",
						EXPLANATION, safety.status().name(), safety.reasonCode().name(), safety.policyVersion(),
						positive ? "REVIEW_SAFETY_GUIDANCE" : "STANDARD_SAFETY_REMINDER",
						positive ? SupportEvaluationService.SAFETY_FALLBACK : STANDARD_SAFETY,
						resourceStatus, resolved.policyVersion(), OffsetDateTime.parse(resolved.resolvedAt()).toInstant(),
						"AI_UNAVAILABLE_FALLBACK", selected));
		return view(evaluation, stored);
	}

	public SupportGuideView get(UUID userId, UUID guideId) {
		return view(userId, writer.required(userId, guideId));
	}

	public HistoryView history(UUID userId, int limit, String cursor) {
		Cursor decoded = decode(cursor);
		var rows = writer.history(userId, decoded == null ? null : decoded.time(), decoded == null ? null : decoded.id(), limit);
		boolean hasMore = rows.size() > limit;
		var page = rows.stream().limit(limit).map(stored -> view(userId, stored)).toList();
		String next = hasMore ? encode(rows.get(limit - 1).guide().generatedAt(), rows.get(limit - 1).guide().id()) : null;
		return new HistoryView(page, next, hasMore);
	}

	private ResourceEligibilityBatchRequest request(EvaluationView evaluation) {
		var requests = new ArrayList<ResourceEligibilityQuery>();
		for (Candidate candidate : CANDIDATES) {
			DomainContributionView domain = evaluation.contributingDomains().stream()
					.filter(value -> value.domain().name().equals(candidate.domain())).findFirst().orElseThrow();
			requests.add(new ResourceEligibilityQuery(
					UUID.nameUUIDFromBytes((evaluation.supportEvaluationId() + candidate.resourceId()).getBytes(StandardCharsets.UTF_8)).toString(),
					candidate.resourceId(), candidate.contentVersion(),
					com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningDomain.valueOf(candidate.domain()),
					RequiredEligibilityRole.PRIMARY,
					"PHQ9".equals(domain.instrument()) ? ScreeningInstrument.PHQ_9 : ScreeningInstrument.GAD_7,
					com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ScreeningLevel.valueOf(domain.screeningLevel().name()),
					SupportTier.valueOf(domain.supportPathway().name()), "vi-VN"));
		}
		return new ResourceEligibilityBatchRequest(requests);
	}

	private List<ResourceDraft> selected(ResourceEligibilityBatchRequest request, List<ResourceEligibilityResult> results) {
		var selected = new ArrayList<ResourceDraft>();
		for (int index = 0; index < results.size() && selected.size() < 4; index++) {
			var result = results.get(index);
			if (result.outcome() != ResourceEligibilityOutcome.ELIGIBLE) continue;
			var query = request.requests().get(index);
			selected.add(new ResourceDraft(UUID.fromString(result.resourceId()), Long.parseLong(result.contentVersion()),
					UUID.fromString(result.publicationId()), query.targetDomain().name(), result.role().name(),
					result.category().name(), result.title(), result.summary(), result.externalUrl()));
		}
		return List.copyOf(selected);
	}

	private String resourceStatus(int selected, List<ResourceEligibilityResult> results) {
		boolean unavailable = results.stream().anyMatch(value -> value.outcome() == ResourceEligibilityOutcome.UNAVAILABLE);
		boolean stale = results.stream().anyMatch(value -> value.outcome() == ResourceEligibilityOutcome.STALE
				|| value.outcome() == ResourceEligibilityOutcome.WITHDRAWN);
		if (selected > 0) return unavailable || stale ? "PARTIAL" : "AVAILABLE";
		if (unavailable) return "UNAVAILABLE";
		if (stale) return "STALE";
		return "EMPTY";
	}

	private SupportGuideView view(UUID userId, StoredGuide stored) {
		return view(evaluations.get(userId, stored.guide().supportEvaluationId()), stored);
	}

	private SupportGuideView view(EvaluationView evaluation, StoredGuide stored) {
		var guide = stored.guide();
		var resources = stored.resources().stream().map(resource -> new ResourceView(resource.resourceId(),
				Long.toString(resource.contentVersion()), resource.publicationId(), resource.domain(),
				resource.eligibilityRole(), resource.category(), resource.title(), resource.summary(), resource.externalUrl())).toList();
		var evidence = evaluation.contributingDomains().stream().map(domain -> new AssessmentProvenance(
				domain.assessmentId(), domain.instrument(), domain.questionnaireVersion(), domain.scoringVersion(),
				domain.screeningLevel().name())).toList();
		return new SupportGuideView(guide.id(), 1, guide.guidePolicyVersion(), guide.supportEvaluationId(),
				guide.generatedAt(), "ONE_TIME_SUPPORT_GUIDE",
				new ExplanationView(guide.explanationCode(), guide.explanationText()),
				new SafetyView(guide.safetyStatus(), guide.safetyReasonCode(), guide.safetyPolicyVersion(),
						guide.safetyGuidanceCode(), guide.safetyGuidance()),
				new ResourceResolutionView(guide.resourceStatus(), guide.resourcePolicyVersion(), guide.resourcesResolvedAt()),
				resources, new ProvenanceView(evaluation.policyVersion(), evidence),
				new PhrasingView("CARE_APPROVED_STANDARD", guide.phrasingStatus()));
	}

	private String hash(GenerateCommand command) {
		try {
			String canonical = "phq9=" + command.phq9AssessmentId() + "\ngad7=" + command.gad7AssessmentId();
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private Cursor decode(String cursor) {
		if (cursor == null || cursor.isBlank()) return null;
		try {
			String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
			return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
		}
		catch (RuntimeException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Support Guide cursor is invalid");
		}
	}

	private String encode(Instant time, UUID id) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString((time + "|" + id).getBytes(StandardCharsets.UTF_8));
	}

	private record Candidate(String resourceId, String contentVersion, String domain) { }
	private record Cursor(Instant time, UUID id) { }
	public record GenerateCommand(UUID phq9AssessmentId, UUID gad7AssessmentId) { }
	public record ExplanationView(String code, String text) { }
	public record SafetyView(String status, String reasonCode, String policyVersion, String guidanceCode, String guidance) { }
	public record ResourceResolutionView(String status, String policyVersion, Instant resolvedAt) { }
	public record ResourceView(UUID resourceId, String contentVersion, UUID publicationId, String domain, String role,
			String category, String title, String summary, String externalUrl) { }
	public record AssessmentProvenance(UUID assessmentId, String instrument, String questionnaireVersion,
			String scoringVersion, String screeningLevel) { }
	public record ProvenanceView(String supportEvaluationPolicyVersion, List<AssessmentProvenance> assessmentResults) { }
	public record PhrasingView(String source, String status) { }
	public record SupportGuideView(UUID supportGuideId, int guideVersion, String guidePolicyVersion,
			UUID supportEvaluationId, Instant generatedAt, String guideType, ExplanationView explanation,
			SafetyView safety, ResourceResolutionView resourceResolution, List<ResourceView> resources,
			ProvenanceView provenance, PhrasingView phrasing) { }
	public record HistoryView(List<SupportGuideView> items, String nextCursor, boolean hasMore) { }
}
