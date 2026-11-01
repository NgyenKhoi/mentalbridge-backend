package com.mentalbridge.care.supportplan;

import static com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewPolicy.actions;
import static com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewPolicy.digest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.entitlement.EntitlementClient;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.reassessment.AdaptiveReviewEvidenceService;
import com.mentalbridge.care.reassessment.AdaptiveReviewEvidenceService.Context;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.screeningepisode.ScreeningEpisodeService;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationV2Service;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewPolicy.Candidate;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.*;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationSnapshot;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.Proposal;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SlotDraft;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.SupportPlanV3View;

@Service
@Transactional(propagation = Propagation.NEVER)
public class AdaptiveSupportPlanReviewService {
	private final SupportPlanV3Service views;
	private final SupportPlanRepository plans;
	private final NativeSupportPlanService nativePlans;
	private final NativeSupportPlanWriter writer;
	private final SupportPlanScheduleService schedules;
	private final AdaptiveReviewEvidenceService evidence;
	private final AdaptiveSupportPlanReviewPolicy policy;
	private final SupportPlanPolicy planPolicy;
	private final SupportEvaluationV2Service evaluations;
	private final ScreeningEpisodeService episodes;
	private final SupportPlanPersonalizationService preferences;
	private final ResourceEligibilityClient eligibility;
	private final EntitlementClient entitlements;
	private final SupportPlanActivityOccurrenceRepository occurrences;
	private final Clock clock;

	public AdaptiveSupportPlanReviewService(SupportPlanV3Service views, SupportPlanRepository plans,
			NativeSupportPlanService nativePlans, NativeSupportPlanWriter writer, SupportPlanScheduleService schedules,
			AdaptiveReviewEvidenceService evidence, AdaptiveSupportPlanReviewPolicy policy, SupportPlanPolicy planPolicy,
			SupportEvaluationV2Service evaluations, ScreeningEpisodeService episodes,
			SupportPlanPersonalizationService preferences, ResourceEligibilityClient eligibility,
			EntitlementClient entitlements, SupportPlanActivityOccurrenceRepository occurrences, Clock clock) {
		this.views = views;
		this.plans = plans;
		this.nativePlans = nativePlans;
		this.writer = writer;
		this.schedules = schedules;
		this.evidence = evidence;
		this.policy = policy;
		this.planPolicy = planPolicy;
		this.evaluations = evaluations;
		this.episodes = episodes;
		this.preferences = preferences;
		this.eligibility = eligibility;
		this.entitlements = entitlements;
		this.occurrences = occurrences;
		this.clock = clock;
	}

	public AdaptiveSupportPlanReviewView review(UUID userId, UUID planId, Long version, UUID summaryId,
			String bearer, UUID correlation) {
		return prepare(userId, planId, version, summaryId, bearer, correlation).view();
	}

	public Confirmation confirm(UUID userId, UUID planId, long version, ConfirmCommand command,
			String bearer, String key, UUID correlation) {
		if ((command.proposedPlanId() == null) != (command.proposedVersion() == null)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Both proposed draft fields are required together");
		}
		String hash = digest("ADAPTIVE_CONFIRM\n" + planId + "\n" + version + "\n" + command);
		var replay = writer.reviewedReplay(userId, key, hash);
		if (replay != null) {
			var result = views.get(userId, replay.planId());
			return "DRAFT_PREPARED".equals(replay.result())
					? new Confirmation(replay.result(), command.optionId(), views.get(userId, planId), result)
					: new Confirmation(replay.result(), command.optionId(), result, null);
		}
		var prepared = prepare(userId, planId, version, command.reassessmentSummaryId(), bearer, correlation);
		var candidate = prepared.candidates().stream().filter(item -> item.option().optionId().equals(command.optionId()))
				.findFirst().orElse(null);
		if ("UNAVAILABLE".equals(prepared.view().currentAdmission()) || "UNAVAILABLE".equals(prepared.access())
				&& (candidate == null || !"NO_CHANGE".equals(candidate.option().executionKind()))) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RESOURCE_ELIGIBILITY_UNAVAILABLE", "Current admission could not be verified");
		}
		if (!prepared.view().reviewFingerprint().equals(command.reviewFingerprint())) throw stale();
		if (candidate == null) throw conflict("ADAPTIVE_REVIEW_OPTION_UNAVAILABLE", "The selected option is no longer available");
		String execution = candidate.option().executionKind();
		if ("NAVIGATE_PROFESSIONAL_SUPPORT".equals(execution)
				|| command.proposedPlanId() != null && !"PREPARE_REPLACEMENT".equals(execution)) {
			throw conflict("ADAPTIVE_REVIEW_OPTION_UNAVAILABLE", "This option does not accept the requested confirmation");
		}
		Runnable guard = () -> guard(userId, prepared);
		SupportPlanV3View result;
		switch (execution) {
			case "NO_CHANGE" -> {
				result = nativePlans.keepReviewed(userId, planId, version, bearer, key, hash, correlation, guard);
				return new Confirmation("KEPT", command.optionId(), result, null);
			}
			case "CONTINUE_CYCLE" -> {
				result = nativePlans.continueReviewed(userId, planId, version, bearer, key, hash, correlation, guard);
				return new Confirmation("CYCLE_CONTINUED", command.optionId(), result, null);
			}
			case "UPDATE_SCHEDULE" -> {
				schedules.updateReviewed(userId, planId, candidate.option().actionId(), version, key,
						candidate.schedule(), hash, guard);
				return new Confirmation("SCHEDULE_UPDATED", command.optionId(), views.get(userId, planId), null);
			}
			case "PREPARE_REPLACEMENT" -> {
				if (command.reassessmentSummaryId() == null || prepared.context().summary() == null
						|| !command.reassessmentSummaryId().equals(prepared.context().summary().summaryId())) throw stale();
				if (command.proposedPlanId() == null) {
					var draft = nativePlans.proposeReviewed(userId, planId, version, candidate.evaluationId(), candidate.proposal(),
							materializationPreferences(userId, planId), bearer, key, hash, correlation, guard);
					return new Confirmation("DRAFT_PREPARED", command.optionId(), views.get(userId, planId), draft);
				}
				var draft = views.get(userId, command.proposedPlanId());
				if (draft.version() != command.proposedVersion()) throw versionConflict();
				if (!matches(userId, prepared.plan(), draft, candidate.proposal(), candidate.evaluationId())) throw stale();
				var replace = new SupportPlanService.ReplacePlanCommand(planId, version, command.reassessmentSummaryId());
				result = nativePlans.replaceReviewed(userId, command.proposedPlanId(), command.proposedVersion(), bearer,
						key, hash, replace, correlation, () -> {
							guard.run();
							if (!matches(userId, prepared.plan(), views.get(userId, draft.supportPlanId()), candidate.proposal(), candidate.evaluationId())) throw stale();
						});
				return new Confirmation("REPLACED", command.optionId(), result, null);
			}
			default -> throw conflict("ADAPTIVE_REVIEW_OPTION_UNAVAILABLE", "The option cannot be confirmed");
		}
	}

	private Prepared prepare(UUID userId, UUID planId, Long expectedVersion, UUID summaryId, String bearer, UUID correlation) {
		var plan = views.get(userId, planId);
		if (expectedVersion != null && plan.version() != expectedVersion) throw versionConflict();
		if (!List.of("ACTIVE", "PAUSED").contains(plan.status())) {
			throw conflict("SUPPORT_PLAN_NOT_REVIEWABLE", "Only the current active or paused plan can be reviewed");
		}
		var context = evidence.read(userId, summaryId, bearer, correlation);
		var personalization = preferences.currentSnapshot(userId).orElse(null);
		String admission = "ADMISSIBLE";
		try { nativePlans.revalidateCurrent(userId, planId, bearer, correlation); }
		catch (ApiException exception) {
			if (exception.status() == HttpStatus.SERVICE_UNAVAILABLE) admission = "UNAVAILABLE";
			else if (List.of("SUPPORT_EVALUATION_STALE", "SUPPORT_PLAN_POLICY_STALE", "RESOURCE_VERSION_STALE",
					"SUPPORT_PLAN_INVALID_CHOICE", "SUPPORT_PLAN_CORE_UNAVAILABLE", "SUPPORT_PLAN_SAFETY_REVIEW_REQUIRED")
					.contains(exception.code())) admission = "INADMISSIBLE";
			else throw exception;
		}
		String access;
		try { access = entitlements.current(userId, bearer, correlation).packageCode() == ServicePackage.FREE ? "FREE" : "ALLOWED"; }
		catch (ApiException exception) {
			if (exception.status() != HttpStatus.SERVICE_UNAVAILABLE) throw exception;
			access = "UNAVAILABLE";
		}
		var patterns = policy.patterns(plan, context.occurrences());
		var contradictions = policy.contradictions(context.summary());
		var conflicts = contradictions.stream().flatMap(item -> item.evidenceRefs().stream()).distinct().limit(10).toList();
		var candidates = new ArrayList<Candidate>();
		boolean due = plan.cycle() != null && "REVIEW_DUE".equals(plan.cycle().state());
		if ("ADMISSIBLE".equals(admission) && (!due || "ALLOWED".equals(access))) {
			var refs = patterns.stream().filter(item -> "REPEATED_HELPFUL".equals(item.kind()))
					.map(AdaptiveSupportPlanReviewPolicy::ref).limit(10).toList();
			candidates.add(new Candidate(policy.option("KEEP_CURRENT", due ? "CONTINUE_CYCLE" : "NO_CHANGE", null, null,
					List.of("CURRENT_PLAN_ADMISSIBLE"), refs, conflicts, List.of()), null, null, null));
		}
		if ("ADMISSIBLE".equals(admission) && "ACTIVE".equals(plan.status()) && "ALLOWED".equals(access)) {
			add(candidates, policy.lowerWorkload(plan, patterns, personalization, conflicts, clock.instant()));
			add(candidates, policy.differentTime(plan, personalization, conflicts, clock.instant()));
		}
		boolean candidateAdmissionUnavailable = false;
		if (!"UNAVAILABLE".equals(admission) && "AVAILABLE".equals(context.state()) && "ALLOWED".equals(access)) {
			try { replacementOptions(userId, plan, context, personalization, patterns, conflicts, admission, bearer, correlation, candidates); }
			catch (ApiException exception) {
				if (exception.status() != HttpStatus.CONFLICT && exception.status() != HttpStatus.SERVICE_UNAVAILABLE) throw exception;
				candidateAdmissionUnavailable = exception.status() == HttpStatus.SERVICE_UNAVAILABLE;
			}
		}
		if (actions(plan).stream().anyMatch(action -> "PROFESSIONAL_SUPPORT".equals(action.actionType()))) {
			var refs = context.summary() == null ? List.<EvidenceRef>of() : context.summary().screening().trends().stream()
					.filter(item -> item.current() != null).map(item -> new EvidenceRef("PHQ9".equals(item.instrument())
							? "SCREENING_PHQ9" : "SCREENING_GAD7", item.current().assessmentId().toString())).toList();
			candidates.add(new Candidate(policy.option("PROFESSIONAL_SUPPORT_NEXT_STEP", "NAVIGATE_PROFESSIONAL_SUPPORT", null, null,
					List.of("PROFESSIONAL_SUPPORT_PRIMARY", "NO_AUTOMATIC_BOOKING"), refs, conflicts, List.of()), null, null, null));
		}
		var unavailable = new ArrayList<UnavailableOption>();
		for (String kind : AdaptiveSupportPlanReviewPolicy.KINDS) {
			if (candidates.stream().noneMatch(item -> kind.equals(item.option().kind()))) {
				String reason = unavailableReason(kind, admission, access, plan, context, personalization, patterns);
				if (candidateAdmissionUnavailable && List.of("ALTERNATIVE_ACTION", "DIFFERENT_FOCUS").contains(kind)) reason = "ADMISSION_UNAVAILABLE";
				unavailable.add(new UnavailableOption(kind, reason));
			}
		}
		var period = programPeriod(userId, plan);
		String fingerprint = fingerprint(plan, context, personalization, period, admission, access, candidates);
		boolean alternative = candidates.stream().anyMatch(item -> !List.of("KEEP_CURRENT", "PROFESSIONAL_SUPPORT_NEXT_STEP").contains(item.option().kind()));
		String outcome = "UNAVAILABLE".equals(admission) ? null : "INADMISSIBLE".equals(admission)
				? "CURRENT_PLAN_NOT_ADMISSIBLE" : alternative ? "CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE" : "CURRENT_PLAN_VALID_NO_BETTER_ALTERNATIVE";
		var view = new AdaptiveSupportPlanReviewView("adaptive-support-plan-review-v1", AdaptiveSupportPlanReviewPolicy.POLICY,
				planId, plan.version(), plan.status(), plan.cycle(), clock.instant(), period,
				"PAUSED".equals(plan.status()) ? "PAUSED" : due ? "REVIEW_DUE" : "EARLY",
				new Reassessment(context.state(), context.reasonCode(), context.summary()), admission, outcome, patterns, contradictions,
				candidates.stream().map(Candidate::option).toList(), unavailable, fingerprint,
				new EvidenceContext(context.comparisonContext().policyVersion(), context.previousPeriod(),
						context.currentPeriod(), "UTC", context.selfReport(), context.occurrences()));
		return new Prepared(view, plan, context, personalization, access, candidates);
	}

	private void replacementOptions(UUID userId, SupportPlanV3View plan, Context context, PersonalizationSnapshot personalization,
			List<Pattern> patterns, List<EvidenceRef> conflicts, String admission, String bearer, UUID correlation, List<Candidate> candidates) {
		var episode = episodes.requiredCompleted(userId, "REASSESSMENT");
		var evaluation = evaluations.getCurrentCompatible(userId, episode.supportEvaluationId());
		if (evaluation.safetyEvidence().status() == SafetyStatus.POSITIVE_SAFETY_SCREEN) return;
		var expected = context.summary().screening().trends().stream().filter(item -> item.current() != null)
				.map(item -> item.current().assessmentId()).sorted().toList();
		if (!evaluation.contributingDomains().stream().map(item -> item.assessmentId()).sorted().toList().equals(expected)) return;
		var storedPreferences = materializationPreferences(userId, plan.supportPlanId());
		var request = planPolicy.request(evaluation, storedPreferences);
		var proposal = planPolicy.compose(request, eligibility.resolve(request.batch(), bearer, correlation), storedPreferences);
		var currentSlots = actions(plan).stream().filter(action -> "RESOURCE".equals(action.actionType()))
				.map(action -> action.provenance().referenceId()).sorted().toList();
		if (!proposal.slots().stream().map(SlotDraft::slotId).sorted().toList().equals(currentSlots)) return;
		var preserved = new ArrayList<SlotDraft>();
		for (var slot : proposal.slots()) {
			var old = actions(plan).stream().filter(action -> "RESOURCE".equals(action.actionType())
					&& slot.slotId().equals(action.provenance().referenceId())).findFirst().orElse(null);
			var available = new ArrayList<SupportPlanPolicy.ResourceDraft>();
			available.add(slot.selectedResource());
			available.addAll(slot.allowedAlternatives());
			var selected = old == null ? slot.selectedResource() : available.stream().filter(resource ->
					resource.resourceId().toString().equals(old.source().sourceId())
					&& Long.toString(resource.contentVersion()).equals(old.source().sourceRevision())).findFirst().orElse(slot.selectedResource());
			preserved.add(new SlotDraft(slot.slotId(), slot.kind(), slot.targetDomain(), slot.purposeCode(), selected,
					available.stream().filter(resource -> !resource.key().equals(selected.key())).toList()));
		}
		var currentOrder = List.of(focusOrder(plan).split(","));
		if (!proposal.families().stream().map(item -> item.targetDomain()).sorted().toList().equals(currentOrder.stream().sorted().toList())) return;
		var orderedFamilies = proposal.families().stream().sorted(java.util.Comparator.comparingInt(item -> currentOrder.indexOf(item.targetDomain()))).toList();
		var base = new Proposal(orderedFamilies, preserved, proposal.resourcePolicyVersion(), proposal.resourcesResolvedAt());
		var changedSlots = preserved.stream().filter(slot -> actions(plan).stream().noneMatch(action -> "RESOURCE".equals(action.actionType())
				&& slot.slotId().equals(action.provenance().referenceId())
				&& slot.selectedResource().resourceId().toString().equals(action.source().sourceId())
				&& Long.toString(slot.selectedResource().contentVersion()).equals(action.source().sourceRevision()))).toList();
		if (!changedSlots.isEmpty()) {
			if (!"INADMISSIBLE".equals(admission) || changedSlots.size() != 1) return;
			var slot = changedSlots.getFirst();
			var action = actions(plan).stream().filter(item -> "RESOURCE".equals(item.actionType())
					&& slot.slotId().equals(item.provenance().referenceId())).findFirst().orElseThrow();
			candidates.add(new Candidate(policy.option("ALTERNATIVE_ACTION", "PREPARE_REPLACEMENT", UUID.fromString(action.actionId()), null,
					List.of("CURRENT_PLAN_NOT_ADMISSIBLE", "EXACT_ADMITTED_REPLACEMENT"),
					context.summary().screening().trends().stream().filter(item -> item.current() != null)
							.map(item -> new EvidenceRef("PHQ9".equals(item.instrument()) ? "SCREENING_PHQ9" : "SCREENING_GAD7", item.current().assessmentId().toString())).toList(),
					conflicts, List.of(new Change("ACTION", action.presentation().title(), slot.selectedResource().title()))),
					null, base, evaluation.supportEvaluationId()));
			return;
		}
		for (var pattern : patterns) {
			if (!"NOT_A_GOOD_FIT".equals(pattern.code())) continue;
			var action = AdaptiveSupportPlanReviewPolicy.action(plan, pattern.actionId());
			var slot = base.slots().stream().filter(item -> item.slotId().equals(action.provenance().referenceId())).findFirst().orElse(null);
			if (slot == null || slot.allowedAlternatives().isEmpty()) continue;
			var alternative = slot.allowedAlternatives().getFirst();
			if (base.slots().stream().anyMatch(item -> !item.slotId().equals(slot.slotId())
					&& item.selectedResource().key().equals(alternative.key()))) continue;
			var changed = base.slots().stream().map(item -> item.slotId().equals(slot.slotId())
					? new SlotDraft(item.slotId(), item.kind(), item.targetDomain(), item.purposeCode(), alternative, List.of()) : item).toList();
			candidates.add(new Candidate(policy.option("ALTERNATIVE_ACTION", "PREPARE_REPLACEMENT", pattern.actionId(), null,
					List.of("REPEATED_NOT_A_GOOD_FIT", "PRACTICAL_FIT_ONLY"), List.of(AdaptiveSupportPlanReviewPolicy.ref(pattern)), conflicts,
					List.of(new Change("ACTION", slot.selectedResource().title(), alternative.title()))), null,
					new Proposal(base.families(), changed, base.resourcePolicyVersion(), base.resourcesResolvedAt()), evaluation.supportEvaluationId()));
			break;
		}
		String domain = personalization == null || personalization.preferredFocus() == null ? null
				: switch (personalization.preferredFocus()) { case ANXIETY -> "ANXIETY_SYMPTOMS"; case MOOD, MOTIVATION -> "DEPRESSIVE_SYMPTOMS"; default -> null; };
		if (domain != null && base.families().size() > 1) {
			var ordered = new ArrayList<>(base.families());
			ordered.sort(java.util.Comparator.comparing(family -> !domain.equals(family.targetDomain())));
			var desired = new Proposal(List.copyOf(ordered), base.slots(), base.resourcePolicyVersion(), base.resourcesResolvedAt());
			if (!sameFocusOrder(plan, desired)) {
				candidates.add(new Candidate(policy.option("DIFFERENT_FOCUS", "PREPARE_REPLACEMENT", null, null,
						List.of("EXPLICIT_FOCUS_PREFERENCE", "ADMITTED_FOCUS_ORDER_ONLY"),
						List.of(new EvidenceRef("PREFERENCES", personalization.personalizationId().toString())), conflicts,
						List.of(new Change("FOCUS_ORDER", focusOrder(plan), String.join(",", ordered.stream().map(item -> item.targetDomain()).toList())))),
						null, desired, evaluation.supportEvaluationId()));
			}
		}
	}

	private boolean sameFocusOrder(SupportPlanV3View plan, Proposal proposal) {
		return focusOrder(plan).equals(String.join(",", proposal.families().stream().map(item -> item.targetDomain()).toList()));
	}

	private String focusOrder(SupportPlanV3View plan) {
		return String.join(",", plan.focuses().stream().map(focus -> focus.actions().stream()
				.filter(action -> List.of("RESOURCE", "PROFESSIONAL_SUPPORT").contains(action.actionType())).findFirst()
				.map(action -> action.purposeCode().startsWith("DEPRESSION")
						|| action.provenance().referenceId().startsWith("DEPRESSIVE") ? "DEPRESSIVE_SYMPTOMS" : "ANXIETY_SYMPTOMS")
				.orElse("UNKNOWN")).toList());
	}

	private boolean matches(UUID userId, SupportPlanV3View current, SupportPlanV3View draft, Proposal proposal, UUID evaluationId) {
		var entity = plans.findByIdAndUserId(draft.supportPlanId(), userId).orElseThrow();
		if (!"DRAFT".equals(draft.status()) || !evaluationId.equals(entity.supportEvaluationId()) || !sameFocusOrder(draft, proposal)) return false;
		var expected = proposal.slots().stream().map(slot -> slot.slotId() + ":" + slot.selectedResource().key()).sorted().toList();
		var actual = actions(draft).stream().filter(action -> "RESOURCE".equals(action.actionType()))
				.map(action -> action.provenance().referenceId() + ":" + action.source().sourceId() + ":" + action.source().sourceRevision()).sorted().toList();
		return expected.equals(actual) && schedules.reviewedDraftSchedulesMatch(current, draft);
	}

	private PersonalizationSnapshot materializationPreferences(UUID userId, UUID planId) {
		var plan = plans.findByIdAndUserId(planId, userId).orElseThrow();
		return plan.personalizationId() == null ? null
				: preferences.snapshot(plan.personalizationId(), userId, plan.personalizationRevision()).orElseThrow(this::stale);
	}

	private ProgramPeriod programPeriod(UUID userId, SupportPlanV3View plan) {
		String timezone = plan.cycle() == null ? SupportPlanCycle.DEFAULT_TIMEZONE : plan.cycle().timezone();
		LocalDate today = clock.instant().atZone(ZoneId.of(timezone)).toLocalDate();
		LocalDate start = plan.cycle() == null ? plan.activatedAt().atZone(ZoneId.of(timezone)).toLocalDate() : plan.cycle().startedOn();
		var rows = occurrences.findBySupportPlanIdAndUserIdAndLocalDateBetweenOrderByScheduledAtAscIdAsc(plan.supportPlanId(), userId, start, today)
				.stream().filter(item -> !item.scheduledAt().isAfter(clock.instant()) && !"CANCELLED".equals(item.state())).toList();
		int paused = plan.cycle() == null ? 0 : plan.cycle().pausedDays();
		return new ProgramPeriod(start, today, timezone, (int) Math.max(1, ChronoUnit.DAYS.between(start, today) + 1), paused,
				plan.cycle() != null && plan.cycle().reviewDueOn() != null && !today.isBefore(plan.cycle().reviewDueOn()), rows.size(),
				(int) rows.stream().filter(item -> "COMPLETED".equals(item.state())).count(),
				(int) rows.stream().filter(item -> "SKIPPED".equals(item.state())).count(),
				(int) rows.stream().filter(item -> "SCHEDULED".equals(item.state())).count());
	}

	private String fingerprint(SupportPlanV3View plan, Context context, PersonalizationSnapshot personalization,
			ProgramPeriod period, String admission, String access, List<Candidate> candidates) {
		var variants = candidates.stream().map(item -> item.option() + ":" + item.schedule() + ":" + semantic(item.proposal()) + ":" + item.evaluationId()).toList();
		return digest(AdaptiveSupportPlanReviewPolicy.POLICY + plan + context.localToken() + context.sourceToken()
				+ personalization + period + admission + access + variants);
	}

	private String semantic(Proposal proposal) {
		return proposal == null ? "" : proposal.families() + ":" + proposal.slots().stream().map(slot ->
				slot.slotId() + ":" + slot.kind() + ":" + slot.selectedResource().key() + ":" + slot.selectedResource().publicationId()
				+ ":" + slot.selectedResource().role()).toList() + proposal.resourcePolicyVersion();
	}

	private void guard(UUID userId, Prepared prepared) {
		if (!Objects.equals(prepared.context().localToken(), evidence.localToken(userId, prepared.context()))
				|| !Objects.equals(prepared.preferences(), preferences.currentSnapshot(userId).orElse(null))
				|| !prepared.plan().equals(views.get(userId, prepared.plan().supportPlanId()))
				|| !prepared.view().programPeriod().equals(programPeriod(userId, prepared.plan()))) throw stale();
	}

	private String unavailableReason(String kind, String admission, String access, SupportPlanV3View plan,
			Context context, PersonalizationSnapshot personalization, List<Pattern> patterns) {
		if ("UNAVAILABLE".equals(admission) || "UNAVAILABLE".equals(access)) return "ADMISSION_UNAVAILABLE";
		if ("FREE".equals(access)) return "ENTITLEMENT_REQUIRED";
		if ("KEEP_CURRENT".equals(kind) && "INADMISSIBLE".equals(admission)) return "CURRENT_PLAN_NOT_ADMISSIBLE";
		if (List.of("LOWER_WORKLOAD", "DIFFERENT_TIME").contains(kind) && "PAUSED".equals(plan.status())) return "PLAN_PAUSED";
		if (List.of("ALTERNATIVE_ACTION", "DIFFERENT_FOCUS").contains(kind) && !"AVAILABLE".equals(context.state())) return "REASSESSMENT_REQUIRED";
		if ("DIFFERENT_TIME".equals(kind) && (personalization == null || personalization.preferredDaypart() == null)) return "NO_TIME_PREFERENCE";
		if (List.of("LOWER_WORKLOAD", "ALTERNATIVE_ACTION").contains(kind) && patterns.stream().noneMatch(item ->
				"ALTERNATIVE_ACTION".equals(kind) ? "NOT_A_GOOD_FIT".equals(item.code()) : List.of("LOW_ENERGY", "NOT_ENOUGH_TIME").contains(item.code()))) return "NO_SUPPORTING_PATTERN";
		return "NO_ADMITTED_CANDIDATE";
	}

	private void add(List<Candidate> candidates, Candidate candidate) { if (candidate != null) candidates.add(candidate); }
	private ApiException versionConflict() { return new ApiException(HttpStatus.PRECONDITION_FAILED, "SUPPORT_PLAN_VERSION_MISMATCH", "SupportPlan version does not match If-Match"); }
	private ApiException stale() { return conflict("ADAPTIVE_REVIEW_STALE", "The review context has changed; reload and explicitly choose again"); }
	private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
	public record ConfirmCommand(String reviewFingerprint, String optionId, UUID reassessmentSummaryId, UUID proposedPlanId, Long proposedVersion) { }
	private record Prepared(AdaptiveSupportPlanReviewView view, SupportPlanV3View plan, Context context,
			PersonalizationSnapshot preferences, String access, List<Candidate> candidates) { }
}
