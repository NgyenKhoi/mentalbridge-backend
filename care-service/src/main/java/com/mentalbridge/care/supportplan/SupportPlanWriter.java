package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.mentalbridge.care.supportplan.SupportPlanPolicy.Proposal;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.ResourceDraft;

@Service
class SupportPlanWriter {

	private final SupportPlanRepository plans;
	private final SupportPlanTemplateFamilyRepository families;
	private final SupportPlanSlotRepository slots;
	private final SupportPlanSlotAlternativeRepository alternatives;
	private final UserProfileRepository profiles;
	private final SupportPlanActivityOccurrenceService activities;
	private final ObjectMapper objectMapper;

	SupportPlanWriter(SupportPlanRepository plans, SupportPlanTemplateFamilyRepository families,
			SupportPlanSlotRepository slots, SupportPlanSlotAlternativeRepository alternatives,
			UserProfileRepository profiles, SupportPlanActivityOccurrenceService activities,
			ObjectMapper objectMapper) {
		this.plans = plans;
		this.families = families;
		this.slots = slots;
		this.alternatives = alternatives;
		this.profiles = profiles;
		this.activities = activities;
		this.objectMapper = objectMapper;
	}

	@Transactional
	StoredPlan persist(UUID userId, String idempotencyKey, String requestHash, EvaluationView evaluation,
			CurrentEntitlementResponse entitlement, Proposal proposal, String rationaleText,
			String safetyGuidanceCode, String safetyGuidance, Instant now) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var replay = plans.findRequest(userId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.orElseThrow().getRequestHash().equals(requestHash)) {
				throw idempotencyConflict();
			}
			return required(userId, replay.orElseThrow().getPlanId());
		}
		var existing = plans.findByUserIdAndStatus(userId, "DRAFT");
		if (existing.isPresent()) {
			plans.insertRequest(userId, idempotencyKey, requestHash, existing.orElseThrow().id(), now);
			return stored(existing.orElseThrow());
		}

		var safety = evaluation.safetyEvidence();
		var plan = new SupportPlanEntity(UUID.randomUUID(), userId, evaluation.supportEvaluationId(),
				evaluation.policyVersion(), evaluation.evaluatedAt(), proposal.resourcePolicyVersion(),
				proposal.resourcesResolvedAt(), entitlement.packageCode().name(), entitlement.source().name(),
				entitlement.policyVersion(), entitlement.version(), entitlement.decidedAt(), rationaleText,
				safety.status().name(), safety.reasonCode().name(), safety.policyVersion(), safetyGuidanceCode,
				safetyGuidance, proposal.slots().size(), now);
		plans.saveAndFlush(plan);

		for (int index = 0; index < proposal.families().size(); index++) {
			var family = proposal.families().get(index);
			families.save(new SupportPlanTemplateFamilyEntity(UUID.randomUUID(), plan.id(), index + 1,
					family.family(), family.targetDomain()));
		}
		families.flush();

		for (int index = 0; index < proposal.slots().size(); index++) {
			var slotDraft = proposal.slots().get(index);
			var slot = new SupportPlanSlotEntity(UUID.randomUUID(), plan.id(), index + 1, slotDraft);
			slots.saveAndFlush(slot);
			for (int alternativeIndex = 0; alternativeIndex < slotDraft.allowedAlternatives().size(); alternativeIndex++) {
				alternatives.save(new SupportPlanSlotAlternativeEntity(UUID.randomUUID(), slot.id(),
						alternativeIndex + 1, slotDraft.allowedAlternatives().get(alternativeIndex)));
			}
		}
		alternatives.flush();
		plans.insertRequest(userId, idempotencyKey, requestHash, plan.id(), now);
		return stored(plan);
	}

	@Transactional
	StoredPlan changeChoices(UUID userId, UUID planId, long expectedVersion, List<Choice> choices, Instant now) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var plan = lockedDraft(userId, planId, expectedVersion);
		applyChoices(plan, choices);
		plan.changeChoices(choices.size(), now);
		plans.saveAndFlush(plan);
		return stored(plan);
	}

	@Transactional
	StoredPlan activate(UUID userId, UUID planId, long expectedVersion, String idempotencyKey,
			String requestHash, List<Choice> choices, Revalidation evidence, UUID correlationId, Instant now) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var replay = commandReplay(userId, idempotencyKey, "ACTIVATE", requestHash);
		if (replay != null) {
			return replay.plan();
		}
		var plan = lockedDraft(userId, planId, expectedVersion);
		if (plans.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "PAUSED")).isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_CURRENT_EXISTS",
					"Another current SupportPlan already exists");
		}
		validateStoredChoices(plan, choices);
		plan.activate(now);
		plans.saveAndFlush(plan);
		activities.activate(plan, now);
		persistCommand(userId, idempotencyKey, "ACTIVATE", requestHash, plan, expectedVersion,
				choices, evidence, null, null, null, null, now);
		plans.insertActivationOutbox(UUID.randomUUID(), plan.id(), plan.version(), correlationId,
				activationPayload(plan, evidence), now);
		return stored(plan);
	}

	@Transactional
	StoredPlan transition(UUID userId, UUID planId, long expectedVersion, String desiredStatus,
			String completionReason, Instant now) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var plan = plans.findByIdAndUserIdForUpdate(planId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"));
		if (desiredStatus.equals(plan.status())) {
			return stored(plan);
		}
		if (plan.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "SUPPORT_PLAN_VERSION_MISMATCH",
					"SupportPlan version does not match If-Match");
		}
		switch (desiredStatus) {
			case "PAUSED" -> {
				if (!"ACTIVE".equals(plan.status())) throw invalidTransition();
				plan.pause(now);
				activities.pause(plan.id(), now);
			}
			case "ACTIVE" -> {
				if (!"PAUSED".equals(plan.status())) throw invalidTransition();
				plan.resume(now);
				activities.resume(plan, now);
			}
			case "COMPLETED" -> {
				if (!List.of("ACTIVE", "PAUSED").contains(plan.status())) throw invalidTransition();
				plan.complete(completionReason, now);
				activities.end(plan.id(), "PLAN_COMPLETED", now);
			}
			case "DISCARDED" -> {
				if (!"DRAFT".equals(plan.status())) throw invalidTransition();
				plan.discard(now);
			}
			default -> throw invalidTransition();
		}
		plans.saveAndFlush(plan);
		return stored(plan);
	}

	@Transactional
	StoredPlan replace(UUID userId, UUID draftId, long draftVersion, UUID currentPlanId,
			long currentVersion, String idempotencyKey, String requestHash, List<Choice> choices,
			Revalidation evidence, UUID reassessmentSummaryId, String reviewOutcome,
			UUID correlationId, Instant now) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var replay = commandReplay(userId, idempotencyKey, "REPLACE", requestHash);
		if (replay != null) {
			return replay.plan();
		}
		var draft = plans.findByIdAndUserIdForUpdate(draftId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"));
		var current = plans.findByIdAndUserIdForUpdate(currentPlanId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"));
		if (draft.version() != draftVersion || current.version() != currentVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "SUPPORT_PLAN_VERSION_MISMATCH",
					"SupportPlan version does not match the replacement request");
		}
		if (!"DRAFT".equals(draft.status()) || !List.of("ACTIVE", "PAUSED").contains(current.status())
				|| draft.id().equals(current.id())) {
			throw invalidTransition();
		}
		validateStoredChoices(draft, choices);
		current.supersede(now);
		activities.end(current.id(), "PLAN_REPLACED", now);
		plans.saveAndFlush(current);
		draft.activate(now);
		plans.saveAndFlush(draft);
		activities.activate(draft, now);
		persistCommand(userId, idempotencyKey, "REPLACE", requestHash, draft, draftVersion,
				choices, evidence, currentPlanId, currentVersion, reassessmentSummaryId, reviewOutcome, now);
		plans.insertActivationOutbox(UUID.randomUUID(), draft.id(), draft.version(), correlationId,
				activationPayload(draft, evidence), now);
		return stored(draft);
	}

	@Transactional(readOnly = true)
	StoredPlan currentDraft(UUID userId) {
		var plan = plans.findByUserIdAndStatus(userId, "DRAFT").orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_PLAN_DRAFT_NOT_FOUND", "Current SupportPlan draft was not found"));
		return stored(plan);
	}

	@Transactional(readOnly = true)
	StoredPlan currentPlan(UUID userId) {
		var plan = plans.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "PAUSED")).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_CURRENT_NOT_FOUND",
						"Current SupportPlan was not found"));
		return stored(plan);
	}

	@Transactional(readOnly = true)
	List<StoredPlan> history(UUID userId, Instant beforeTime, UUID beforeId, int limit) {
		return plans.findTerminalHistory(userId, beforeTime, beforeId, limit)
				.stream().map(this::stored).toList();
	}

	@Transactional(readOnly = true)
	SupportPlanRepository.RequestRow request(UUID userId, String idempotencyKey) {
		return plans.findRequest(userId, idempotencyKey).orElse(null);
	}

	@Transactional(readOnly = true)
	CommandOutcome command(UUID userId, String idempotencyKey) {
		var command = plans.findCommand(userId, idempotencyKey).orElse(null);
		if (command == null) {
			return null;
		}
		var selections = plans.findCommandSelections(userId, idempotencyKey).stream()
				.map(row -> new ChoiceReference(row.getSlotKey(), row.getResourceId(), row.getContentVersion()))
				.toList();
		return new CommandOutcome(required(userId, command.getPlanId()), command.getCommandType(),
				command.getRequestHash(), command.getExpectedVersion(), command.getResultingVersion(),
				command.getResultingStatus(), command.getResultingUpdatedAt(), selections,
				command.getSourcePlanId(), command.getSourcePlanVersion(), command.getReassessmentSummaryId(),
				command.getReplacementReviewOutcome());
	}

	@Transactional(readOnly = true)
	StoredPlan required(UUID userId, UUID planId) {
		var plan = plans.findByIdAndUserId(planId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"));
		return stored(plan);
	}

	private void applyChoices(SupportPlanEntity plan, List<Choice> choices) {
		Map<String, Choice> bySlot = choiceMap(choices);
		Set<String> selectedVersions = new HashSet<>();
		for (SupportPlanSlotEntity slot : slots.findBySupportPlanIdOrderByOrdinal(plan.id())) {
			var current = slot.selectedResource();
			var slotAlternatives = alternatives.findBySupportPlanSlotIdOrderByOrdinal(slot.id());
			preserveCandidate(slot, current, slotAlternatives);
			var choice = bySlot.remove(slot.slotKey());
			if (choice == null) {
				if ("CORE".equals(slot.slotKind())) {
					throw invalidChoice("Every core SupportPlan slot must remain selected");
				}
				slot.removeSelection();
				continue;
			}
			var selected = candidate(slot, slotAlternatives, choice.resource());
			if (selected == null || ("CORE".equals(slot.slotKind()) && !"PRIMARY".equals(selected.role()))) {
				throw invalidChoice("A requested resource version is not admitted for its SupportPlan slot");
			}
			if (!selectedVersions.add(selected.key())) {
				throw invalidChoice("The same exact resource version cannot fill more than one SupportPlan slot");
			}
			slot.select(selected);
		}
		if (!bySlot.isEmpty() || choices.isEmpty() || choices.size() > 5) {
			throw invalidChoice("SupportPlan choices must contain one through five known slot selections");
		}
		slots.flush();
		alternatives.flush();
	}

	private void validateStoredChoices(SupportPlanEntity plan, List<Choice> choices) {
		Map<String, Choice> bySlot = choiceMap(choices);
		Set<String> selectedVersions = new HashSet<>();
		for (SupportPlanSlotEntity slot : slots.findBySupportPlanIdOrderByOrdinal(plan.id())) {
			var selected = slot.selectedResource();
			var choice = bySlot.remove(slot.slotKey());
			if (selected == null) {
				if ("CORE".equals(slot.slotKind())) {
					throw invalidChoice("Every core SupportPlan slot must be selected before activation");
				}
				if (choice != null) {
					throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_CHOICES_CHANGED",
							"SupportPlan choices changed before activation");
				}
				continue;
			}
			if (choice == null || !selected.key().equals(choice.resource().key())) {
				throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_CHOICES_CHANGED",
						"SupportPlan choices changed before activation");
			}
			if (!selectedVersions.add(selected.key())) {
				throw invalidChoice("The same exact resource version cannot fill more than one SupportPlan slot");
			}
		}
		if (!bySlot.isEmpty() || choices.isEmpty() || choices.size() > 5) {
			throw invalidChoice("SupportPlan choices do not match the current draft");
		}
	}

	private Map<String, Choice> choiceMap(List<Choice> choices) {
		Map<String, Choice> bySlot = new HashMap<>();
		for (Choice choice : choices) {
			if (bySlot.put(choice.slotId(), choice) != null) {
				throw invalidChoice("A SupportPlan slot can appear only once");
			}
		}
		return bySlot;
	}

	private ResourceDraft candidate(SupportPlanSlotEntity slot,
			List<SupportPlanSlotAlternativeEntity> slotAlternatives, ResourceDraft requested) {
		var current = slot.selectedResource();
		if (current != null && current.key().equals(requested.key())) {
			return current;
		}
		return slotAlternatives.stream().map(SupportPlanSlotAlternativeEntity::resource)
				.filter(resource -> resource.key().equals(requested.key())).findFirst().orElse(null);
	}

	private void preserveCandidate(SupportPlanSlotEntity slot, ResourceDraft current,
			List<SupportPlanSlotAlternativeEntity> slotAlternatives) {
		if (current == null || slotAlternatives.stream().map(SupportPlanSlotAlternativeEntity::resource)
				.anyMatch(resource -> resource.key().equals(current.key()))) {
			return;
		}
		int ordinal = slotAlternatives.stream().mapToInt(SupportPlanSlotAlternativeEntity::ordinal).max().orElse(0) + 1;
		alternatives.save(new SupportPlanSlotAlternativeEntity(UUID.randomUUID(), slot.id(), ordinal, current));
	}

	private SupportPlanEntity lockedDraft(UUID userId, UUID planId, long expectedVersion) {
		var plan = plans.findByIdAndUserIdForUpdate(planId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"));
		if (!"DRAFT".equals(plan.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_NOT_DRAFT",
					"Only a SupportPlan draft accepts this command");
		}
		if (plan.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "SUPPORT_PLAN_VERSION_MISMATCH",
					"SupportPlan version does not match If-Match");
		}
		return plan;
	}

	private void persistCommand(UUID userId, String idempotencyKey, String commandType, String requestHash,
			SupportPlanEntity plan, long expectedVersion, List<Choice> choices, Revalidation evidence,
			UUID sourcePlanId, Long sourcePlanVersion, UUID reassessmentSummaryId, String reviewOutcome,
			Instant now) {
		var entitlement = evidence.entitlement();
		plans.insertCommand(userId, idempotencyKey, commandType, requestHash, plan.id(), expectedVersion,
				plan.version(), plan.status(), plan.updatedAt(), evidence.evaluationPolicyVersion(),
				entitlement.packageCode().name(), entitlement.source().name(), entitlement.policyVersion(),
				entitlement.version(), entitlement.decidedAt(), evidence.resourcePolicyVersion(),
				evidence.resourcesResolvedAt(), now, sourcePlanId, sourcePlanVersion,
				reassessmentSummaryId, reviewOutcome);
		for (int index = 0; index < choices.size(); index++) {
			var choice = choices.get(index);
			plans.insertCommandSelection(userId, idempotencyKey, index + 1, choice.slotId(),
					choice.resource().resourceId(), choice.resource().contentVersion());
		}
	}

	private CommandOutcome commandReplay(UUID userId, String idempotencyKey, String commandType, String requestHash) {
		var outcome = command(userId, idempotencyKey);
		if (outcome == null) {
			return null;
		}
		if (!outcome.commandType().equals(commandType) || !outcome.requestHash().equals(requestHash)) {
			throw idempotencyConflict();
		}
		return outcome;
	}

	private void lockOwner(UUID userId) {
		if (profiles.findByIdForUpdate(userId).isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Care profile was not found");
		}
	}

	private StoredPlan stored(SupportPlanEntity plan) {
		var storedSlots = new ArrayList<StoredSlot>();
		for (SupportPlanSlotEntity slot : slots.findBySupportPlanIdOrderByOrdinal(plan.id())) {
			storedSlots.add(new StoredSlot(slot,
					alternatives.findBySupportPlanSlotIdOrderByOrdinal(slot.id())));
		}
		return new StoredPlan(plan, families.findBySupportPlanIdOrderByOrdinal(plan.id()), List.copyOf(storedSlots));
	}

	private String activationPayload(SupportPlanEntity plan, Revalidation evidence) {
		try {
			return objectMapper.writeValueAsString(new ActivationPayload(plan.id(), plan.userId(), plan.version(),
					plan.activatedAt(), evidence.evaluationPolicyVersion(), evidence.resourcePolicyVersion(),
					evidence.entitlement().packageCode().name()));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("SupportPlan activation event could not be serialized", exception);
		}
	}

	private ApiException invalidChoice(String message) {
		return new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_INVALID_CHOICE", message);
	}

	private ApiException invalidTransition() {
		return new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_TRANSITION_INVALID",
				"The requested SupportPlan lifecycle transition is not allowed");
	}

	private ApiException idempotencyConflict() {
		return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
				"Idempotency-Key was already used with a different SupportPlan command");
	}

	record Choice(String slotId, ResourceDraft resource) { }
	record ChoiceReference(String slotId, UUID resourceId, long contentVersion) { }
	record Revalidation(String evaluationPolicyVersion, CurrentEntitlementResponse entitlement,
			String resourcePolicyVersion, Instant resourcesResolvedAt) { }
	record StoredSlot(SupportPlanSlotEntity slot, List<SupportPlanSlotAlternativeEntity> alternatives) { }
	record StoredPlan(SupportPlanEntity plan, List<SupportPlanTemplateFamilyEntity> families,
			List<StoredSlot> slots) { }
	record CommandOutcome(StoredPlan plan, String commandType, String requestHash, long expectedVersion,
			long resultingVersion, String resultingStatus, Instant resultingUpdatedAt,
			List<ChoiceReference> selections, UUID sourcePlanId, Long sourcePlanVersion,
			UUID reassessmentSummaryId, String replacementReviewOutcome) { }
	record ActivationPayload(UUID supportPlanId, UUID userId, long planVersion, Instant activatedAt,
			String evaluationPolicyVersion, String resourceEligibilityPolicyVersion, String packageCode) { }
}
