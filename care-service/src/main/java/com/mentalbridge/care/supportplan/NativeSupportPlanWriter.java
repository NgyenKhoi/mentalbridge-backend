package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationV2Service.EvaluationView;
import com.mentalbridge.care.supportplan.PlanActionEntity.Presentation;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.ActivityMode;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationSnapshot;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.Proposal;
import com.mentalbridge.care.supportplan.SupportPlanWriter.Revalidation;

@Service
class NativeSupportPlanWriter {
	static final String EMOTION_POLICY = "support-plan-emotion-actions-v1";
	static final String POLICY = "support-plan-journal-actions-v1";
	static final String PROPOSAL_POLICY = "support-plan-proposal-v1";
	static final String JOURNAL_INTENT = "short-reflection";
	static final String JOURNAL_INTENT_POLICY = "journal-writing-intent-v1";
	private final SupportPlanRepository plans;
	private final SupportPlanTemplateFamilyRepository families;
	private final SupportPlanFocusRepository focuses;
	private final PlanActionRepository actions;
	private final UserProfileRepository profiles;
	private final SupportPlanActivityOccurrenceService activities;
	private final SupportPlanScheduleCommandRepository scheduleCommands;
	private final SupportPlanScheduleService schedules;
	private final ObjectMapper mapper;

	NativeSupportPlanWriter(SupportPlanRepository plans, SupportPlanTemplateFamilyRepository families,
			SupportPlanFocusRepository focuses, PlanActionRepository actions, UserProfileRepository profiles,
			SupportPlanActivityOccurrenceService activities, SupportPlanScheduleCommandRepository scheduleCommands,
			SupportPlanScheduleService schedules, ObjectMapper mapper) {
		this.plans = plans;
		this.families = families;
		this.focuses = focuses;
		this.actions = actions;
		this.profiles = profiles;
		this.activities = activities;
		this.scheduleCommands = scheduleCommands;
		this.schedules = schedules;
		this.mapper = mapper;
	}

	@Transactional(readOnly = true)
	Snapshot required(UUID userId, UUID planId) {
		var plan = plans.findByIdAndUserId(planId, userId).orElseThrow(this::notFound);
		if (plan.modelVersion() != 3 || !List.of(POLICY, EMOTION_POLICY, PROPOSAL_POLICY).contains(plan.planPolicyVersion())) {
			throw conflict("SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED", "This command requires an admitted native plan");
		}
		return new Snapshot(plan, families.findBySupportPlanIdOrderByOrdinal(planId),
				actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(planId, userId));
	}

	@Transactional
	UUID propose(UUID userId, String key, String hash, EvaluationView evaluation,
			CurrentEntitlementResponse entitlement, Proposal proposal, PersonalizationSnapshot preferences,
			String safetyCode, String safetyCopy, Instant now) {
		return propose(userId, key, hash, evaluation, entitlement, proposal, preferences, safetyCode, safetyCopy,
				now, null, null, null);
	}

	@Transactional
	UUID proposeReviewed(UUID userId, UUID currentId, long currentVersion, String key, String hash,
			EvaluationView evaluation, CurrentEntitlementResponse entitlement, Proposal proposal,
			PersonalizationSnapshot preferences, String safetyCode, String safetyCopy, Instant now, Runnable guard) {
		return propose(userId, key, hash, evaluation, entitlement, proposal, preferences, safetyCode, safetyCopy,
				now, currentId, currentVersion, guard);
	}

	private UUID propose(UUID userId, String key, String hash, EvaluationView evaluation,
			CurrentEntitlementResponse entitlement, Proposal proposal, PersonalizationSnapshot preferences,
			String safetyCode, String safetyCopy, Instant now, UUID currentId, Long currentVersion, Runnable guard) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		rejectOtherLedgers(userId, key, "REQUEST");
		var replay = plans.findRequest(userId, key).orElse(null);
		if (replay != null) {
			if (!hash.equals(replay.getRequestHash())) throw idempotencyConflict();
			return required(userId, replay.getPlanId()).plan().id();
		}
		var current = currentId == null ? null : lockedCurrent(userId, currentId, currentVersion);
		if (guard != null) guard.run();
		var existing = plans.findByUserIdAndStatus(userId, "DRAFT").orElse(null);
		if (existing != null) {
			throw conflict("SUPPORT_PLAN_DRAFT_EXISTS", "Review or explicitly discard the existing draft before a new native proposal");
		}
		boolean emotion = preferences == null || !preferences.provided()
				|| preferences.preferredActivityModes().contains(ActivityMode.EMOTION_CHECK_IN);
		boolean journal = preferences != null && preferences.preferredActivityModes().contains(ActivityMode.JOURNALING);
		int professionalCount = (int) proposal.families().stream()
				.filter(family -> family.family().endsWith("_PROFESSIONAL_ADJUNCT")).count();
		var safety = evaluation.safetyEvidence();
		var plan = SupportPlanEntity.nativeDraft(UUID.randomUUID(), userId, evaluation.supportEvaluationId(),
				evaluation.policyVersion(), evaluation.evaluatedAt(), PROPOSAL_POLICY, entitlement.packageCode().name(),
				entitlement.source().name(), entitlement.policyVersion(), entitlement.version(), entitlement.decidedAt(),
				"DOMAIN_AWARE_WELLBEING_SUPPORT", "Các hoạt động được MentalBridge kiểm tra theo bối cảnh sàng lọc và lựa chọn của bạn.",
				safety.status().name(), safety.reasonCode().name(), safety.policyVersion(), safetyCode, safetyCopy,
				proposal.slots().size() + professionalCount + (emotion ? 1 : 0) + (journal ? 1 : 0) + 1, now);
		plan.snapshotPersonalization(preferences);
		plan.initializeCycle(profiles.findTimezoneByAccountId(userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Care profile was not found")));
		plan = plans.saveAndFlush(plan);
		for (int index = 0; index < evaluation.contributingDomains().size(); index++) {
			String targetDomain = evaluation.contributingDomains().get(index).domain().name();
			var family = proposal.families().stream().filter(value -> targetDomain.equals(value.targetDomain()))
					.findFirst().orElseThrow(() -> conflict("SUPPORT_PLAN_POLICY_STALE", "The proposal focus domains no longer match the evaluation"));
			families.save(new SupportPlanTemplateFamilyEntity(UUID.randomUUID(), plan.id(), index + 1,
					family.family(), family.targetDomain(), family.focusTitle(), family.focusRationale(), family.focusReasonCode()));
		}
		var focusByDomain = new HashMap<String, UUID>();
		int ordinal = 1;
		for (int index = 0; index < proposal.families().size(); index++) {
			var family = proposal.families().get(index);
			var focus = focuses.saveAndFlush(new SupportPlanFocusEntity(UUID.randomUUID(), plan.id(), userId,
					index + 1, family.focusTitle() == null ? "ANXIETY_SYMPTOMS".equals(family.targetDomain())
							? "Hỗ trợ bình tâm" : "Hỗ trợ tâm trạng" : family.focusTitle(), family.focusRationale(), now));
			focusByDomain.put(family.targetDomain(), focus.id());
			if (family.family().endsWith("_PROFESSIONAL_ADJUNCT")) {
				actions.save(new PlanActionEntity(UUID.randomUUID(), plan.id(), focus.id(), userId, ordinal++,
						"PROFESSIONAL_SUPPORT", "CONNECT_WITH_PROFESSIONAL", null,
						new SourceReference("CONSULTATION", "PROFESSIONAL_SUPPORT_PATHWAY", "specialist-discovery", "1",
								PROPOSAL_POLICY, null, null, now), new Provenance("SYSTEM_POLICY", family.family(), PROPOSAL_POLICY, now),
						new Presentation("Kết nối với chuyên gia", "Hỗ trợ chuyên môn là hướng chính; hoạt động tự hỗ trợ chỉ bổ trợ.",
								null, null), now));
			}
			for (var slot : proposal.slots()) {
				if (!family.targetDomain().equals(slot.targetDomain())) continue;
				var resource = slot.selectedResource();
				actions.save(new PlanActionEntity(UUID.randomUUID(), plan.id(), focus.id(), userId, ordinal++, "RESOURCE",
						slot.purposeCode(), null, new SourceReference("CONTENT", "RESOURCE_VERSION", resource.resourceId().toString(),
								Long.toString(resource.contentVersion()), proposal.resourcePolicyVersion(), resource.publicationId(),
								proposal.resourcePolicyVersion(), proposal.resourcesResolvedAt()),
						new Provenance("SYSTEM_POLICY", slot.slotId(), SupportPlanService.SELECTION_POLICY_VERSION, now),
						new Presentation(resource.title(), resource.summary(), null, resource.externalUrl()), now));
			}
		}
		if (emotion) {
			UUID focusId = focusByDomain.get(proposal.families().get(0).targetDomain());
			actions.save(new PlanActionEntity(UUID.randomUUID(), plan.id(), focusId, userId, ordinal++, "EMOTION_CHECK_IN",
					"NOTICE_SELF_REPORTED_EMOTIONS", preferences != null && preferences.provided()
							? "Bạn đã chọn check-in cảm xúc trong hoạt động ưu tiên."
							: "Ghi nhận ngắn cảm xúc mỗi ngày để nhìn lại cảm nhận của bạn.",
					new SourceReference("JOURNAL_AI", "EMOTION_CHECK_IN_CAPABILITY", "daily-emotion-check-in", "1",
							"daily-emotion-check-in-v1", null, null, now),
					emotionProvenance(preferences, now),
					new Presentation("Check-in cảm xúc", preferences != null && preferences.provided()
							? "Ghi nhận cảm xúc của bạn theo nhịp đã chọn; không phải đánh giá chẩn đoán."
							: "Ghi nhận cảm xúc của bạn mỗi ngày; không phải đánh giá chẩn đoán.",
							"Mở check-in cảm xúc, lưu lựa chọn rồi quay lại kế hoạch để kiểm tra lần thực hiện này.", null), now));
		}
		if (journal) {
			UUID focusId = focusByDomain.get(proposal.families().get(0).targetDomain());
			actions.save(new PlanActionEntity(UUID.randomUUID(), plan.id(), focusId, userId, ordinal++, "JOURNAL",
					"WRITE_SHORT_REFLECTION", "Bạn đã chọn viết nhật ký trong hoạt động ưu tiên.",
					new SourceReference("JOURNAL_AI", "JOURNAL_TEMPLATE", JOURNAL_INTENT, "1",
							JOURNAL_INTENT_POLICY, null, null, now),
					new Provenance("USER_SELECTED", preferences.personalizationId().toString(), Long.toString(preferences.revision()), now),
					new Presentation("Viết vài dòng nhật ký", "Dành một khoảng thời gian ngắn để viết về điều bạn muốn ghi lại.",
							"Mở nhật ký, viết và lưu bài mới rồi quay lại kế hoạch để kiểm tra lần viết này. Nội dung bài viết vẫn riêng tư.", null), now));
		}
		var questionnaire = evaluation.contributingDomains().stream()
				.filter(domain -> "PHQ9".equals(domain.instrument())).findFirst()
				.orElse(evaluation.contributingDomains().get(0));
		actions.save(new PlanActionEntity(UUID.randomUUID(), plan.id(),
				focusByDomain.get(proposal.families().get(0).targetDomain()), userId, ordinal,
				"REASSESSMENT_SCREENING", "REVIEW_SUPPORT_AFTER_CYCLE", null,
				new SourceReference("CARE", "QUESTIONNAIRE_DEFINITION", questionnaire.questionnaireDefinitionId().toString(),
						questionnaire.questionnaireVersion(), PROPOSAL_POLICY, null, null, now),
				new Provenance("SYSTEM_POLICY", SupportPlanCycle.POLICY, PROPOSAL_POLICY, now),
				new Presentation("Kiểm tra lại sau 14 ngày", "Nhìn lại cảm nhận và chọn bước tiếp theo cho kế hoạch.", null, null), now));
		families.flush();
		actions.flush();
		activities.prepareDraft(plan, now);
		if (current != null) schedules.preserveReviewedDraft(current, plan, now);
		plans.insertRequest(userId, key, hash, plan.id(), now);
		return plan.id();
	}

	private Provenance emotionProvenance(PersonalizationSnapshot preferences, Instant now) {
		return preferences != null && preferences.provided()
				? new Provenance("USER_SELECTED", preferences.personalizationId().toString(), Long.toString(preferences.revision()), now)
				: new Provenance("SYSTEM_POLICY", PROPOSAL_POLICY, PROPOSAL_POLICY, now);
	}

	@Transactional
	UUID continueCycle(UUID userId, UUID planId, long expectedVersion, String key, String hash,
			CurrentEntitlementResponse entitlement, Instant now) {
		return continueCycle(userId, planId, expectedVersion, key, hash, entitlement, null, now, null);
	}

	@Transactional
	UUID continueReviewed(UUID userId, UUID planId, long expectedVersion, String key, String hash,
			Revalidation evidence, Instant now, Runnable guard) {
		return continueCycle(userId, planId, expectedVersion, key, hash, evidence.entitlement(), evidence, now, guard);
	}

	private UUID continueCycle(UUID userId, UUID planId, long expectedVersion, String key, String hash,
			CurrentEntitlementResponse entitlement, Revalidation evidence, Instant now, Runnable guard) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var replay = replay(userId, key, hash, "CYCLE_CONTINUE");
		if (replay != null) return replay;
		var plan = plans.findByIdAndUserIdForUpdate(planId, userId).orElseThrow(this::notFound);
		if (plan.version() != expectedVersion) throw versionConflict();
		if (guard != null) guard.run();
		var cycle = SupportPlanCycle.view(plan, now);
		if (cycle == null || !"REVIEW_DUE".equals(cycle.state())) {
			throw conflict("SUPPORT_PLAN_CYCLE_NOT_REVIEW_DUE", "Only a review-due proposal cycle can continue");
		}
		plan.continueCycle(now);
		plans.saveAndFlush(plan);
		activities.continueCycle(plan, now);
		var resource = actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(plan.id(), userId)
				.stream().filter(action -> "RESOURCE".equals(action.actionType())).findFirst().orElseThrow(() ->
						conflict("SUPPORT_PLAN_POLICY_STALE", "The proposal resource provenance is missing"));
		// The ledger records the entitlement decided for this command, as ACTIVATE/REPLACE do.
		plans.insertCommand(userId, key, "CYCLE_CONTINUE", hash, plan.id(), expectedVersion, plan.version(),
				plan.status(), plan.updatedAt(), plan.evaluationPolicyVersion(), entitlement.packageCode().name(),
				entitlement.source().name(), entitlement.policyVersion(), entitlement.version(),
				entitlement.decidedAt(), evidence == null ? resource.sourceEligibilityPolicyVersion() : evidence.resourcePolicyVersion(),
				evidence == null ? resource.sourceVerifiedAt() : evidence.resourcesResolvedAt(), now,
				null, null, null, null, null);
		return plan.id();
	}

	@Transactional
	UUID activate(UUID userId, UUID planId, long expectedVersion, String key, String hash,
			Revalidation evidence, UUID correlationId, Instant now) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var replay = replay(userId, key, hash, "ACTIVATE");
		if (replay != null) return replay;
		var plan = lockedDraft(userId, planId, expectedVersion);
		if (plans.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "PAUSED")).isPresent()) {
			throw conflict("SUPPORT_PLAN_CURRENT_EXISTS", "Another current SupportPlan requires an explicit reassessment-backed replacement");
		}
		activateActions(plan, now, false);
		recordCommand(plan, expectedVersion, key, hash, "ACTIVATE", evidence, null, null, null, null, correlationId, now);
		return plan.id();
	}

	@Transactional
	UUID replace(UUID userId, UUID draftId, long expectedVersion, UUID currentId, long currentVersion,
			UUID summaryId, String outcome, String key, String hash, Revalidation evidence, UUID correlationId, Instant now) {
		return replace(userId, draftId, expectedVersion, currentId, currentVersion, summaryId, outcome, key, hash,
				evidence, correlationId, now, null, false);
	}

	@Transactional
	UUID replaceReviewed(UUID userId, UUID draftId, long expectedVersion, UUID currentId, long currentVersion,
			UUID summaryId, String outcome, String key, String hash, Revalidation evidence, UUID correlationId,
			Instant now, Runnable guard) {
		return replace(userId, draftId, expectedVersion, currentId, currentVersion, summaryId, outcome, key, hash,
				evidence, correlationId, now, java.util.Objects.requireNonNull(guard), true);
	}

	private UUID replace(UUID userId, UUID draftId, long expectedVersion, UUID currentId, long currentVersion,
			UUID summaryId, String outcome, String key, String hash, Revalidation evidence, UUID correlationId,
			Instant now, Runnable guard, boolean reviewed) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var replay = replay(userId, key, hash, "REPLACE");
		if (replay != null) return replay;
		var draft = lockedDraft(userId, draftId, expectedVersion);
		var current = plans.findByIdAndUserIdForUpdate(currentId, userId).orElseThrow(this::notFound);
		if (current.version() != currentVersion) throw versionConflict();
		if (current.id().equals(draft.id()) || !List.of("ACTIVE", "PAUSED").contains(current.status())) {
			throw conflict("SUPPORT_PLAN_TRANSITION_INVALID", "Replacement requires the current active or paused plan");
		}
		if (guard != null) guard.run();
		current.supersede(now);
		activities.end(current.id(), "PLAN_REPLACED", now);
		plans.saveAndFlush(current);
		activateActions(draft, now, reviewed);
		recordCommand(draft, expectedVersion, key, hash, "REPLACE", evidence, currentId, currentVersion,
				summaryId, outcome, correlationId, now);
		return draft.id();
	}

	UUID replay(UUID userId, String key, String hash, String type) {
		rejectOtherLedgers(userId, key, "COMMAND");
		var existing = plans.findCommand(userId, key).orElse(null);
		if (existing == null) return null;
		if (!hash.equals(existing.getRequestHash()) || !type.equals(existing.getCommandType())) throw idempotencyConflict();
		return required(userId, existing.getPlanId()).plan().id();
	}

	@Transactional(readOnly = true)
	ReviewedReplay reviewedReplay(UUID userId, String key, String hash) {
		var request = plans.findRequest(userId, key).orElse(null);
		var command = plans.findCommand(userId, key).orElse(null);
		var schedule = scheduleCommands.findByUserIdAndIdempotencyKey(userId, key).orElse(null);
		int matches = (request == null ? 0 : 1) + (command == null ? 0 : 1) + (schedule == null ? 0 : 1);
		if (matches == 0) return null;
		if (matches != 1) throw idempotencyConflict();
		if (request != null) {
			if (!hash.equals(request.getRequestHash())) throw idempotencyConflict();
			return new ReviewedReplay("DRAFT_PREPARED", request.getPlanId());
		}
		if (schedule != null) {
			if (!hash.equals(schedule.requestHash())) throw idempotencyConflict();
			return new ReviewedReplay("SCHEDULE_UPDATED", schedule.supportPlanId());
		}
		if (!hash.equals(command.getRequestHash())) throw idempotencyConflict();
		String result = switch (command.getCommandType()) {
			case "CYCLE_CONTINUE" -> "CYCLE_CONTINUED";
			case "REPLACE" -> "REPLACED";
			default -> throw idempotencyConflict();
		};
		return new ReviewedReplay(result, command.getPlanId());
	}

	@Transactional
	UUID keepReviewed(UUID userId, UUID planId, long expectedVersion, Runnable guard) {
		lockOwner(userId);
		var plan = lockedCurrent(userId, planId, expectedVersion);
		guard.run();
		return plan.id();
	}

	private SupportPlanEntity lockedCurrent(UUID userId, UUID planId, long expectedVersion) {
		var plan = plans.findByIdAndUserIdForUpdate(planId, userId).orElseThrow(this::notFound);
		if (plan.version() != expectedVersion) throw versionConflict();
		if (!List.of("ACTIVE", "PAUSED").contains(plan.status())) {
			throw conflict("SUPPORT_PLAN_NOT_REVIEWABLE", "Review requires the current active or paused plan");
		}
		return plan;
	}

	private void rejectOtherLedgers(UUID userId, String key, String ledger) {
		if ((!"REQUEST".equals(ledger) && plans.findRequest(userId, key).isPresent())
				|| (!"COMMAND".equals(ledger) && plans.findCommand(userId, key).isPresent())
				|| scheduleCommands.findByUserIdAndIdempotencyKey(userId, key).isPresent()) throw idempotencyConflict();
	}

	private void activateActions(SupportPlanEntity plan, Instant now, boolean reviewed) {
		var values = actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(plan.id(), plan.userId());
		if (values.size() != plan.actionCount() || values.stream().anyMatch(action -> !"DRAFT".equals(action.status()))) {
			throw conflict("SUPPORT_PLAN_POLICY_STALE", "Native draft actions no longer match the admitted proposal");
		}
		plan.activate(now);
		plans.saveAndFlush(plan);
		values.forEach(action -> action.activate(now));
		actions.flush();
		if (reviewed) activities.activateReviewedDraft(plan, now);
		else activities.activateUserDraft(plan, now);
	}

	private void recordCommand(SupportPlanEntity plan, long expectedVersion, String key, String hash,
			String type, Revalidation evidence, UUID currentId, Long currentVersion, UUID summaryId,
			String outcome, UUID correlationId, Instant now) {
		var entitlement = evidence.entitlement();
		plans.insertCommand(plan.userId(), key, type, hash, plan.id(), expectedVersion, plan.version(), plan.status(),
				plan.updatedAt(), evidence.evaluationPolicyVersion(), entitlement.packageCode().name(), entitlement.source().name(),
				entitlement.policyVersion(), entitlement.version(), entitlement.decidedAt(), evidence.resourcePolicyVersion(),
				evidence.resourcesResolvedAt(), now, currentId, currentVersion, summaryId, null, outcome);
		int ordinal = 1;
		for (var action : actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(plan.id(), plan.userId())) {
			if (!"RESOURCE".equals(action.actionType())) continue;
			plans.insertCommandSelection(plan.userId(), key, ordinal++, action.provenanceReferenceId(),
					UUID.fromString(action.sourceId()), Long.parseLong(action.sourceRevision()));
		}
		try {
			var payload = new SupportPlanWriter.ActivationPayload(plan.id(), plan.userId(), plan.version(), plan.activatedAt(),
					evidence.evaluationPolicyVersion(), evidence.resourcePolicyVersion(), entitlement.packageCode().name());
			plans.insertActivationOutbox(UUID.randomUUID(), plan.id(), plan.version(), correlationId,
					mapper.writeValueAsString(payload), now);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Native plan activation fact could not be serialized", exception);
		}
	}

	private SupportPlanEntity lockedDraft(UUID userId, UUID planId, long version) {
		var plan = plans.findByIdAndUserIdForUpdate(planId, userId).orElseThrow(this::notFound);
		if (plan.modelVersion() != 3 || !List.of(POLICY, EMOTION_POLICY, PROPOSAL_POLICY).contains(plan.planPolicyVersion()) || !"DRAFT".equals(plan.status())) {
			throw conflict("SUPPORT_PLAN_NOT_DRAFT", "An admitted native draft is required");
		}
		if (plan.version() != version) throw versionConflict();
		return plan;
	}

	private void lockOwner(UUID userId) {
		profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"PROFILE_NOT_FOUND", "Care profile was not found"));
	}

	private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"); }
	private ApiException versionConflict() { return new ApiException(HttpStatus.PRECONDITION_FAILED, "SUPPORT_PLAN_VERSION_MISMATCH", "SupportPlan version does not match If-Match"); }
	private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
	private ApiException idempotencyConflict() { return conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used with a different plan command"); }

	record Snapshot(SupportPlanEntity plan, List<SupportPlanTemplateFamilyEntity> families, List<PlanActionEntity> actions) { }
	record ReviewedReplay(String result, UUID planId) { }
}
