package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationV2Service.EvaluationView;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.Proposal;

@Service
class SupportPlanWriter {

	private final SupportPlanRepository plans;
	private final SupportPlanTemplateFamilyRepository families;
	private final SupportPlanSlotRepository slots;
	private final SupportPlanSlotAlternativeRepository alternatives;
	private final UserProfileRepository profiles;

	SupportPlanWriter(SupportPlanRepository plans, SupportPlanTemplateFamilyRepository families,
			SupportPlanSlotRepository slots, SupportPlanSlotAlternativeRepository alternatives,
			UserProfileRepository profiles) {
		this.plans = plans;
		this.families = families;
		this.slots = slots;
		this.alternatives = alternatives;
		this.profiles = profiles;
	}

	@Transactional
	StoredPlan persist(UUID userId, String idempotencyKey, String requestHash, EvaluationView evaluation,
			CurrentEntitlementResponse entitlement, Proposal proposal, String rationaleText,
			String safetyGuidanceCode, String safetyGuidance, Instant now) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		if (profiles.findByIdForUpdate(userId).isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Care profile was not found");
		}
		var replay = plans.findRequest(userId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.orElseThrow().getRequestHash().equals(requestHash)) {
				throw conflict();
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

	@Transactional(readOnly = true)
	StoredPlan current(UUID userId) {
		var plan = plans.findByUserIdAndStatus(userId, "DRAFT").orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_PLAN_DRAFT_NOT_FOUND", "Current SupportPlan draft was not found"));
		return stored(plan);
	}

	@Transactional(readOnly = true)
	SupportPlanRepository.RequestRow request(UUID userId, String idempotencyKey) {
		return plans.findRequest(userId, idempotencyKey).orElse(null);
	}

	@Transactional(readOnly = true)
	StoredPlan required(UUID userId, UUID planId) {
		var plan = plans.findByIdAndUserId(planId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_PLAN_DRAFT_NOT_FOUND", "Current SupportPlan draft was not found"));
		return stored(plan);
	}

	private StoredPlan stored(SupportPlanEntity plan) {
		var storedSlots = new ArrayList<StoredSlot>();
		for (SupportPlanSlotEntity slot : slots.findBySupportPlanIdOrderByOrdinal(plan.id())) {
			storedSlots.add(new StoredSlot(slot,
					alternatives.findBySupportPlanSlotIdOrderByOrdinal(slot.id())));
		}
		return new StoredPlan(plan, families.findBySupportPlanIdOrderByOrdinal(plan.id()), List.copyOf(storedSlots));
	}

	private ApiException conflict() {
		return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
				"Idempotency-Key was already used with different SupportPlan evidence");
	}

	record StoredSlot(SupportPlanSlotEntity slot, List<SupportPlanSlotAlternativeEntity> alternatives) { }
	record StoredPlan(SupportPlanEntity plan, List<SupportPlanTemplateFamilyEntity> families,
			List<StoredSlot> slots) { }
}
