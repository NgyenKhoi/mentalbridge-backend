package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.entitlement.EntitlementClient;
import com.mentalbridge.care.reassessment.ReassessmentSummaryService;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.screeningepisode.ScreeningEpisodeService;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationService;
import com.mentalbridge.care.support.SupportEvaluationV2Service;
import com.mentalbridge.care.supportplan.NativeSupportPlanWriter.Snapshot;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.ActivityMode;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SelectedResource;
import com.mentalbridge.care.supportplan.SupportPlanWriter.Revalidation;

@Service
@Transactional(propagation = Propagation.NEVER)
public class NativeSupportPlanService {
	private final NativeSupportPlanWriter writer;
	private final SupportPlanWriter legacy;
	private final SupportPlanRepository plans;
	private final SupportPlanV3Service views;
	private final EntitlementClient entitlements;
	private final SupportEvaluationV2Service evaluations;
	private final ScreeningEpisodeService episodes;
	private final SupportPlanPersonalizationService preferences;
	private final SupportPlanPolicy policy;
	private final ResourceEligibilityClient eligibility;
	private final ReassessmentSummaryService summaries;
	private final Clock clock;

	public NativeSupportPlanService(NativeSupportPlanWriter writer, SupportPlanWriter legacy, SupportPlanRepository plans,
			SupportPlanV3Service views, EntitlementClient entitlements, SupportEvaluationV2Service evaluations,
			ScreeningEpisodeService episodes, SupportPlanPersonalizationService preferences, SupportPlanPolicy policy,
			ResourceEligibilityClient eligibility, ReassessmentSummaryService summaries, Clock clock) {
		this.writer = writer;
		this.legacy = legacy;
		this.plans = plans;
		this.views = views;
		this.entitlements = entitlements;
		this.evaluations = evaluations;
		this.episodes = episodes;
		this.preferences = preferences;
		this.policy = policy;
		this.eligibility = eligibility;
		this.summaries = summaries;
		this.clock = clock;
	}

	public SupportPlanV3Service.SupportPlanV3View propose(UUID userId, UUID evaluationId, String bearer,
			String key, UUID correlationId) {
		String hash = hash("NATIVE_PROPOSE\n" + evaluationId);
		var existing = plans.findRequest(userId, key).orElse(null);
		if (existing != null) {
			if (!hash.equals(existing.getRequestHash())) throw idempotencyConflict();
			writer.required(userId, existing.getPlanId());
			return views.get(userId, existing.getPlanId());
		}
		var personalization = preferences.currentSnapshot(userId).orElse(null);
		var entitlement = paid(userId, bearer, correlationId);
		var evaluation = evaluations.getCurrentCompatible(userId, evaluationId);
		episodes.requiredEvaluationContext(userId, evaluationId);
		var request = policy.request(evaluation, personalization);
		var proposal = policy.compose(request, eligibility.resolve(request.batch(), bearer, correlationId), personalization);
		boolean positive = evaluation.safetyEvidence().status() == SafetyStatus.POSITIVE_SAFETY_SCREEN;
		UUID id = writer.propose(userId, key, hash, evaluation, entitlement, proposal, personalization,
				positive ? "REVIEW_SAFETY_GUIDANCE" : "STANDARD_SAFETY_REMINDER",
				positive ? SupportEvaluationService.SAFETY_FALLBACK
						: "Nếu bạn cảm thấy không an toàn, hãy chủ động tìm hỗ trợ trực tiếp phù hợp tại khu vực bạn đã chọn.",
				clock.instant());
		return views.get(userId, id);
	}

	public SupportPlanV3Service.SupportPlanV3View activate(UUID userId, UUID draftId, long version,
			String bearer, String key, UUID correlationId) {
		String hash = hash("NATIVE_ACTIVATE\n" + draftId + "\n" + version);
		var replay = writer.replay(userId, key, hash, "ACTIVATE");
		if (replay != null) return views.get(userId, replay);
		var draft = draft(userId, draftId, version);
		var evidence = revalidate(userId, bearer, correlationId, draft, paid(userId, bearer, correlationId));
		UUID activated = writer.activate(userId, draftId, version, key, hash, evidence, correlationId, clock.instant());
		return views.get(userId, activated);
	}

	public ReplacementReview reviewReplacement(UUID userId, UUID draftId, long version, String bearer,
			SupportPlanService.ReplacePlanCommand command, UUID correlationId) {
		var reviewed = prepareReplacement(userId, draftId, version, bearer, command, correlationId);
		return reviewed.view();
	}

	public SupportPlanV3Service.SupportPlanV3View replace(UUID userId, UUID draftId, long version, String bearer,
			String key, SupportPlanService.ReplacePlanCommand command, UUID correlationId) {
		String hash = hash("NATIVE_REPLACE\n" + draftId + "\n" + version + "\n" + command.currentSupportPlanId()
				+ "\n" + command.currentVersion() + "\n" + command.reassessmentSummaryId());
		var replay = writer.replay(userId, key, hash, "REPLACE");
		if (replay != null) return views.get(userId, replay);
		var reviewed = prepareReplacement(userId, draftId, version, bearer, command, correlationId);
		if ("CURRENT_PLAN_VALID_NO_BETTER_ALTERNATIVE".equals(reviewed.view().outcome())) {
			throw conflict("SUPPORT_PLAN_REPLACEMENT_UNCHANGED", "The proposed plan has no different admitted action intent");
		}
		UUID applied = writer.replace(userId, draftId, version, command.currentSupportPlanId(), command.currentVersion(),
				command.reassessmentSummaryId(), reviewed.view().outcome(), key, hash, reviewed.evidence(), correlationId, clock.instant());
		return views.get(userId, applied);
	}

	public SupportPlanV3Service.SupportPlanV3View transition(UUID userId, UUID planId, long version, String state,
			String completionReason) {
		if (completionReason != null && (!"COMPLETED".equals(state)
				|| !List.of("USER_DECISION", "PLAN_NO_LONGER_FITS", "OTHER").contains(completionReason))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "SUPPORT_PLAN_COMPLETION_REASON_INVALID", "Completion reason is invalid for this transition");
		}
		writer.required(userId, planId);
		legacy.transition(userId, planId, version, state, completionReason, clock.instant());
		return views.get(userId, planId);
	}

	public History history(UUID userId, int limit, String cursor) {
		java.time.Instant before = null;
		UUID beforeId = null;
		if (cursor != null) {
			try {
				var parts = new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
				before = java.time.Instant.parse(parts[0]);
				beforeId = UUID.fromString(parts[1]);
			}
			catch (RuntimeException exception) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "SupportPlan history cursor is invalid");
			}
		}
		var rows = legacy.history(userId, before, beforeId, limit + 1);
		boolean more = rows.size() > limit;
		String next = null;
		if (more) {
			var last = rows.get(limit - 1).plan();
			next = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
					(last.updatedAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
		}
		return new History(rows.stream().limit(limit).map(row -> views.get(userId, row.plan().id())).toList(), next, more);
	}

	private PreparedReplacement prepareReplacement(UUID userId, UUID draftId, long version, String bearer,
			SupportPlanService.ReplacePlanCommand command, UUID correlationId) {
		var draft = draft(userId, draftId, version);
		var current = plans.findByIdAndUserId(command.currentSupportPlanId(), userId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"));
		if (current.version() != command.currentVersion()) throw versionConflict();
		if (!List.of("ACTIVE", "PAUSED").contains(current.status()) || current.id().equals(draftId)) {
			throw conflict("SUPPORT_PLAN_TRANSITION_INVALID", "Replacement requires the current active or paused plan");
		}
		var summary = summaries.currentForPlanReview(userId, command.reassessmentSummaryId());
		var evaluation = evaluations.getCurrentCompatible(userId, draft.plan().supportEvaluationId());
		var expected = evaluation.contributingDomains().stream().collect(java.util.stream.Collectors.toMap(
				SupportEvaluationV2Service.DomainContributionView::instrument,
				SupportEvaluationV2Service.DomainContributionView::assessmentId));
		var actual = summary.screening().trends().stream().filter(trend -> trend.current() != null)
				.collect(java.util.stream.Collectors.toMap(ReassessmentSummaryView.ScreeningTrend::instrument,
						trend -> trend.current().assessmentId()));
		if (!expected.equals(actual)) {
			throw conflict("REASSESSMENT_SCREENING_CONTEXT_MISMATCH", "The latest reassessment and proposed plan must use the same screening pair");
		}
		var entitlement = paid(userId, bearer, correlationId);
		var evidence = revalidate(userId, bearer, correlationId, draft, entitlement);
		String unavailable = null;
		try {
			if (current.modelVersion() == 3) revalidate(userId, bearer, correlationId, writer.required(userId, current.id()), entitlement);
			else revalidateLegacy(userId, bearer, correlationId, current);
		}
		catch (ApiException exception) {
			if (!List.of("SUPPORT_EVALUATION_STALE", "SUPPORT_PLAN_POLICY_STALE", "RESOURCE_VERSION_STALE",
					"SUPPORT_PLAN_INVALID_CHOICE", "SUPPORT_PLAN_CORE_UNAVAILABLE").contains(exception.code())) throw exception;
			unavailable = exception.code();
		}
		var currentView = views.get(userId, current.id());
		var draftView = views.get(userId, draftId);
		boolean changed = !actionIntents(currentView).equals(actionIntents(draftView));
		String outcome = unavailable != null ? "CURRENT_PLAN_NOT_ADMISSIBLE" : changed
				? "CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE" : "CURRENT_PLAN_VALID_NO_BETTER_ALTERNATIVE";
		var reason = unavailable != null ? List.of(unavailable, "PROPOSED_PLAN_ADMISSIBLE")
				: List.of("CURRENT_PLAN_ADMISSIBLE", changed ? "ADMITTED_ACTION_INTENT_DIFFERS" : "EXACT_ACTION_INTENT_UNCHANGED");
		return new PreparedReplacement(new ReplacementReview(outcome, reason, currentView, draftView, summary,
				clock.instant()), evidence);
	}

	private Set<String> actionIntents(SupportPlanV3Service.SupportPlanV3View view) {
		var values = new HashSet<String>();
		for (var focus : view.focuses()) for (var action : focus.actions()) {
			if ("ENDED".equals(action.status())) continue;
			var source = action.source();
			values.add(action.actionType() + ":" + (source == null ? "" : source.ownerDomain() + ":"
					+ source.sourceKind() + ":" + source.sourceId() + ":" + source.sourceRevision()));
		}
		return values;
	}

	private Revalidation revalidate(UUID userId, String bearer, UUID correlation, Snapshot snapshot,
			CurrentEntitlementResponse entitlement) {
		var plan = snapshot.plan();
		var evaluation = evaluations.getCurrentCompatible(userId, plan.supportEvaluationId());
		episodes.requiredEvaluationContext(userId, plan.supportEvaluationId());
		var selections = new ArrayList<SelectedResource>();
		for (var action : snapshot.actions()) {
			if (!"RESOURCE".equals(action.actionType())) continue;
			if (!"CONTENT".equals(action.sourceOwner()) || !"RESOURCE_VERSION".equals(action.sourceKind())
					|| !"SYSTEM_POLICY".equals(action.provenanceType())
					|| !SupportPlanService.SELECTION_POLICY_VERSION.equals(action.provenanceReferenceVersion())) throw stale();
			selections.add(new SelectedResource(action.provenanceReferenceId(), UUID.fromString(action.sourceId()),
					Long.parseLong(action.sourceRevision())));
		}
		var request = policy.revalidationRequest(evaluation, selections);
		if (!snapshot.families().stream().map(family -> family.family() + ":" + family.targetDomain()).toList()
				.equals(request.families().stream().map(family -> family.family() + ":" + family.targetDomain()).toList())) throw stale();
		long requiredProfessional = request.families().stream().filter(family -> family.family().endsWith("_PROFESSIONAL_ADJUNCT")).count();
		long actualProfessional = snapshot.actions().stream().filter(action -> "PROFESSIONAL_SUPPORT".equals(action.actionType())
				&& !"ENDED".equals(action.status()) && "CONSULTATION".equals(action.sourceOwner())
				&& "PROFESSIONAL_SUPPORT_PATHWAY".equals(action.sourceKind())).count();
		if (requiredProfessional != actualProfessional) throw stale();
		long emotions = snapshot.actions().stream().filter(action -> "EMOTION_CHECK_IN".equals(action.actionType())).count();
		var preference = plan.personalizationId() == null ? null
				: preferences.snapshot(plan.personalizationId(), userId, plan.personalizationRevision()).orElseThrow(this::stale);
		boolean selectedEmotion = preference != null && preference.preferredActivityModes().contains(ActivityMode.EMOTION_CHECK_IN);
		if (emotions != (selectedEmotion ? 1 : 0)) throw stale();
		var result = policy.validate(request, eligibility.resolve(request.batch(), bearer, correlation));
		return new Revalidation(evaluation.policyVersion(), entitlement, result.resourcePolicyVersion(), result.resourcesResolvedAt());
	}

	private void revalidateLegacy(UUID userId, String bearer, UUID correlation, SupportPlanEntity plan) {
		var stored = legacy.required(userId, plan.id());
		var evaluation = evaluations.getCurrentCompatible(userId, plan.supportEvaluationId());
		var selections = stored.slots().stream().filter(value -> value.slot().selectedResource() != null)
				.map(value -> new SelectedResource(value.slot().slotKey(), value.slot().selectedResource().resourceId(),
						value.slot().selectedResource().contentVersion())).toList();
		var request = policy.revalidationRequest(evaluation, selections);
		if (!stored.families().stream().map(value -> value.family() + ":" + value.targetDomain()).toList()
				.equals(request.families().stream().map(value -> value.family() + ":" + value.targetDomain()).toList())) throw stale();
		policy.validate(request, eligibility.resolve(request.batch(), bearer, correlation));
	}

	private Snapshot draft(UUID userId, UUID id, long version) {
		var snapshot = writer.required(userId, id);
		if (!"DRAFT".equals(snapshot.plan().status())) throw conflict("SUPPORT_PLAN_NOT_DRAFT", "Only a native draft accepts this command");
		if (snapshot.plan().version() != version) throw versionConflict();
		return snapshot;
	}

	private CurrentEntitlementResponse paid(UUID userId, String bearer, UUID correlation) {
		var value = entitlements.current(userId, bearer, correlation);
		if (value.packageCode() == ServicePackage.FREE) throw new ApiException(HttpStatus.FORBIDDEN,
				"SUPPORT_PLAN_ENTITLEMENT_REQUIRED", "A current Plus or Premium entitlement is required");
		return value;
	}

	private String hash(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
	}
	private ApiException stale() { return conflict("SUPPORT_PLAN_POLICY_STALE", "Native actions no longer match current Care admission policy"); }
	private ApiException versionConflict() { return new ApiException(HttpStatus.PRECONDITION_FAILED, "SUPPORT_PLAN_VERSION_MISMATCH", "SupportPlan version does not match If-Match"); }
	private ApiException idempotencyConflict() { return conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used with a different plan command"); }
	private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
	public record ReplacementReview(String outcome, List<String> rationaleCodes, SupportPlanV3Service.SupportPlanV3View currentPlan,
			SupportPlanV3Service.SupportPlanV3View proposedPlan, ReassessmentSummaryView reassessmentSummary, java.time.Instant reviewedAt) { }
	public record History(List<SupportPlanV3Service.SupportPlanV3View> items, String nextCursor, boolean hasMore) { }
	private record PreparedReplacement(ReplacementReview view, Revalidation evidence) { }
}
