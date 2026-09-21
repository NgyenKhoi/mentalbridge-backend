package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.entitlement.EntitlementClient;
import com.mentalbridge.care.resourceeligibility.ResourceEligibilityClient;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.support.SupportEvaluationService;
import com.mentalbridge.care.support.SupportEvaluationV2Service;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.ResourceDraft;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SelectedResource;
import com.mentalbridge.care.supportplan.SupportPlanWriter.Choice;
import com.mentalbridge.care.supportplan.SupportPlanWriter.CommandOutcome;
import com.mentalbridge.care.supportplan.SupportPlanWriter.Revalidation;
import com.mentalbridge.care.supportplan.SupportPlanWriter.StoredPlan;

@Service
public class SupportPlanService {

	public static final String SELECTION_POLICY_VERSION = "mb-support-plan-selection-v1";
	private static final String RATIONALE = "MentalBridge Ä‘Ã£ ghÃ©p cÃ¡c lá»±a chá»n há»— trá»£ sá»©c khá»e tá»•ng quÃ¡t theo tá»«ng miá»n sÃ ng lá»c, chÃ­nh sÃ¡ch Care vÃ  phiÃªn báº£n tÃ i nguyÃªn Ä‘Ã£ Ä‘Æ°á»£c duyá»‡t. ÄÃ¢y khÃ´ng pháº£i cháº©n Ä‘oÃ¡n hay káº¿ hoáº¡ch Ä‘iá»u trá»‹.";
	private static final String STANDARD_SAFETY = "Náº¿u tÃ¬nh tráº¡ng cá»§a báº¡n thay Ä‘á»•i hoáº·c báº¡n cáº£m tháº¥y khÃ´ng an toÃ n, hÃ£y chá»§ Ä‘á»™ng tÃ¬m há»— trá»£ trá»±c tiáº¿p phÃ¹ há»£p táº¡i khu vá»±c báº¡n Ä‘Ã£ chá»n.";
	private static final String DISCLAIMER = "SupportPlan nÃ y há»— trá»£ sá»©c khá»e tá»•ng quÃ¡t vÃ  tá»± quáº£n lÃ½; khÃ´ng pháº£i cháº©n Ä‘oÃ¡n, Ä‘Æ¡n thuá»‘c hoáº·c káº¿ hoáº¡ch Ä‘iá»u trá»‹, vÃ  khÃ´ng thay tháº¿ há»— trá»£ an toÃ n hay há»— trá»£ chuyÃªn mÃ´n.";

	private final EntitlementClient entitlements;
	private final SupportEvaluationV2Service evaluations;
	private final ResourceEligibilityClient eligibility;
	private final SupportPlanPolicy policy;
	private final SupportPlanWriter writer;
	private final Clock clock;

	public SupportPlanService(EntitlementClient entitlements, SupportEvaluationV2Service evaluations,
			ResourceEligibilityClient eligibility, SupportPlanPolicy policy, SupportPlanWriter writer, Clock clock) {
		this.entitlements = entitlements;
		this.evaluations = evaluations;
		this.eligibility = eligibility;
		this.policy = policy;
		this.writer = writer;
		this.clock = clock;
	}

	public SupportPlanView propose(UUID userId, String bearerToken, String idempotencyKey,
			UUID correlationId, ProposeCommand command) {
		String requestHash = hash("supportEvaluationId=" + command.sourceSupportEvaluationId());
		var replay = writer.request(userId, idempotencyKey);
		if (replay != null) {
			if (!replay.getRequestHash().equals(requestHash)) {
				throw idempotencyConflict();
			}
			return view(writer.required(userId, replay.getPlanId()));
		}

		CurrentEntitlementResponse entitlement = paidEntitlement(userId, bearerToken, correlationId);
		var evaluation = evaluations.getCurrentCompatible(userId, command.sourceSupportEvaluationId());
		var proposalRequest = policy.request(evaluation);
		var resolved = eligibility.resolve(proposalRequest.batch(), bearerToken, correlationId);
		var proposal = policy.compose(proposalRequest, resolved);
		boolean safetyPositive = evaluation.safetyEvidence().status() == SafetyStatus.POSITIVE_SAFETY_SCREEN;
		var stored = writer.persist(userId, idempotencyKey, requestHash, evaluation, entitlement, proposal,
				RATIONALE, safetyPositive ? "REVIEW_SAFETY_GUIDANCE" : "STANDARD_SAFETY_REMINDER",
				safetyPositive ? SupportEvaluationService.SAFETY_FALLBACK : STANDARD_SAFETY, clock.instant());
		return view(stored);
	}

	public SupportPlanView changeChoices(UUID userId, String bearerToken, UUID planId, long expectedVersion,
			UUID correlationId, ReplaceChoicesCommand command) {
		var stored = writer.required(userId, planId);
		validateDraftVersion(stored, expectedVersion);
		var choices = admittedChoices(stored, command.slotSelections());
		if (sameChoices(stored, choices)) {
			return view(stored);
		}
		revalidate(userId, bearerToken, correlationId, stored, choices);
		return view(writer.changeChoices(userId, planId, expectedVersion, choices, clock.instant()));
	}

	public SupportPlanView activate(UUID userId, String bearerToken, UUID planId, long expectedVersion,
			String idempotencyKey, UUID correlationId) {
		String requestHash = hash("ACTIVATE\n" + planId + "\n" + expectedVersion);
		var replay = replay(userId, idempotencyKey, "ACTIVATE", requestHash);
		if (replay != null) {
			return view(replay);
		}
		var stored = writer.required(userId, planId);
		validateDraftVersion(stored, expectedVersion);
		var selections = stored.slots().stream().filter(slot -> slot.slot().selectedResource() != null)
				.map(slot -> new SlotSelection(slot.slot().slotKey(), slot.slot().selectedResource().resourceId(),
						Long.toString(slot.slot().selectedResource().contentVersion())))
				.toList();
		var choices = admittedChoices(stored, selections);
		var evidence = revalidate(userId, bearerToken, correlationId, stored, choices);
		return view(writer.activate(userId, planId, expectedVersion, idempotencyKey, requestHash,
				choices, evidence, correlationId, clock.instant()));
	}

	public SupportPlanView currentDraft(UUID userId) {
		return view(writer.currentDraft(userId));
	}

	public SupportPlanView currentPlan(UUID userId) {
		return view(writer.currentPlan(userId));
	}

	public SupportPlanView changeStatus(UUID userId, UUID planId, long expectedVersion, String targetStatus) {
		if (!List.of("ACTIVE", "PAUSED", "COMPLETED", "DISCARDED").contains(targetStatus)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "SUPPORT_PLAN_STATUS_INVALID",
					"Target status must be ACTIVE, PAUSED, COMPLETED, or DISCARDED");
		}
		return view(writer.transition(userId, planId, expectedVersion, targetStatus, clock.instant()));
	}

	public SupportPlanView replace(UUID userId, String bearerToken, UUID draftId, long draftVersion,
			UUID correlationId, ReplacePlanCommand command) {
		var draft = writer.required(userId, draftId);
		var current = writer.required(userId, command.currentSupportPlanId());
		if ("ACTIVE".equals(draft.plan().status()) && "SUPERSEDED".equals(current.plan().status())) {
			return view(draft);
		}
		validateDraftVersion(draft, draftVersion);
		var selections = draft.slots().stream().filter(slot -> slot.slot().selectedResource() != null)
				.map(slot -> new SlotSelection(slot.slot().slotKey(), slot.slot().selectedResource().resourceId(),
						Long.toString(slot.slot().selectedResource().contentVersion())))
				.toList();
		var choices = admittedChoices(draft, selections);
		revalidate(userId, bearerToken, correlationId, draft, choices);
		return view(writer.replace(userId, draftId, draftVersion, command.currentSupportPlanId(),
				command.currentVersion(), clock.instant()));
	}

	private Revalidation revalidate(UUID userId, String bearerToken, UUID correlationId,
			StoredPlan stored, List<Choice> choices) {
		var entitlement = paidEntitlement(userId, bearerToken, correlationId);
		var evaluation = evaluations.getCurrentCompatible(userId, stored.plan().supportEvaluationId());
		var selected = choices.stream().map(choice -> new SelectedResource(choice.slotId(),
				choice.resource().resourceId(), choice.resource().contentVersion())).toList();
		var request = policy.revalidationRequest(evaluation, selected);
		assertTemplateCompatibility(stored, request.families(), request.slots());
		var result = policy.validate(request, eligibility.resolve(request.batch(), bearerToken, correlationId));
		return new Revalidation(evaluation.policyVersion(), entitlement, result.resourcePolicyVersion(),
				result.resourcesResolvedAt());
	}

	private CurrentEntitlementResponse paidEntitlement(UUID userId, String bearerToken, UUID correlationId) {
		var entitlement = entitlements.current(userId, bearerToken, correlationId);
		if (entitlement.packageCode() == ServicePackage.FREE) {
			throw new ApiException(HttpStatus.FORBIDDEN, "SUPPORT_PLAN_ENTITLEMENT_REQUIRED",
					"A current PLUS or PREMIUM entitlement is required for SupportPlan");
		}
		return entitlement;
	}

	private void validateDraftVersion(StoredPlan stored, long expectedVersion) {
		if (!"DRAFT".equals(stored.plan().status())) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_NOT_DRAFT",
					"Only a SupportPlan draft accepts this command");
		}
		if (stored.plan().version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "SUPPORT_PLAN_VERSION_MISMATCH",
					"SupportPlan version does not match If-Match");
		}
	}

	private void assertTemplateCompatibility(StoredPlan stored,
			List<SupportPlanPolicy.FamilyDraft> families, List<SupportPlanPolicy.SlotSpec> slots) {
		var storedFamilies = stored.families().stream()
				.map(family -> family.family() + ':' + family.templateVersion() + ':' + family.targetDomain()).toList();
		var currentFamilies = families.stream()
				.map(family -> family.family() + ":1:" + family.targetDomain()).toList();
		if (!storedFamilies.equals(currentFamilies)) {
			throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_POLICY_STALE",
					"SupportPlan template policy changed before the command");
		}
		var specs = new HashMap<String, SupportPlanPolicy.SlotSpec>();
		slots.forEach(slot -> specs.put(slot.slotId(), slot));
		for (var storedSlot : stored.slots()) {
			var slot = storedSlot.slot();
			var spec = specs.get(slot.slotKey());
			if (spec == null || !spec.kind().equals(slot.slotKind())
					|| !spec.targetDomain().equals(slot.targetDomain())
					|| !spec.purposeCode().equals(slot.purposeCode())) {
				throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_POLICY_STALE",
						"SupportPlan slot policy changed before the command");
			}
		}
	}

	private List<Choice> admittedChoices(StoredPlan stored, List<SlotSelection> selections) {
		Map<String, SupportPlanWriter.StoredSlot> bySlot = new HashMap<>();
		stored.slots().forEach(slot -> bySlot.put(slot.slot().slotKey(), slot));
		var choices = new ArrayList<Choice>();
		for (SlotSelection selection : selections) {
			var slot = bySlot.get(selection.slotId());
			if (slot == null) {
				throw invalidChoice();
			}
			long contentVersion;
			try {
				contentVersion = Long.parseLong(selection.contentVersion());
			}
			catch (NumberFormatException exception) {
				throw invalidChoice();
			}
			var requestedKey = selection.resourceId() + ":" + contentVersion;
			var candidates = new ArrayList<ResourceDraft>();
			if (slot.slot().selectedResource() != null) {
				candidates.add(slot.slot().selectedResource());
			}
			candidates.addAll(slot.alternatives().stream()
					.map(SupportPlanSlotAlternativeEntity::resource).toList());
			var resource = candidates.stream().filter(candidate -> candidate.key().equals(requestedKey))
					.findFirst().orElseThrow(this::invalidChoice);
			choices.add(new Choice(selection.slotId(), resource));
		}
		return List.copyOf(choices);
	}

	private boolean sameChoices(StoredPlan stored, List<Choice> choices) {
		Map<String, String> requested = new HashMap<>();
		for (Choice choice : choices) {
			if (requested.put(choice.slotId(), choice.resource().key()) != null) {
				return false;
			}
		}
		Map<String, String> current = new HashMap<>();
		stored.slots().stream().filter(slot -> slot.slot().selectedResource() != null)
				.forEach(slot -> current.put(slot.slot().slotKey(), slot.slot().selectedResource().key()));
		return current.equals(requested);
	}

	private CommandOutcome replay(UUID userId, String idempotencyKey, String type, String requestHash) {
		var replay = writer.command(userId, idempotencyKey);
		if (replay == null) {
			return null;
		}
		if (!type.equals(replay.commandType()) || !requestHash.equals(replay.requestHash())) {
			throw idempotencyConflict();
		}
		return replay;
	}

	private SupportPlanView view(StoredPlan stored) {
		var selections = stored.slots().stream().filter(slot -> slot.slot().selectedResource() != null)
				.collect(java.util.stream.Collectors.toMap(slot -> slot.slot().slotKey(),
						slot -> slot.slot().selectedResource()));
		var plan = stored.plan();
		return view(stored, selections, plan.status(), plan.version(), plan.updatedAt(), plan.activatedAt());
	}

	private SupportPlanView view(CommandOutcome outcome) {
		Map<String, ResourceDraft> selections = new HashMap<>();
		for (var reference : outcome.selections()) {
			var storedSlot = outcome.plan().slots().stream()
					.filter(slot -> slot.slot().slotKey().equals(reference.slotId())).findFirst().orElseThrow();
			var candidates = new ArrayList<ResourceDraft>();
			if (storedSlot.slot().selectedResource() != null) {
				candidates.add(storedSlot.slot().selectedResource());
			}
			candidates.addAll(storedSlot.alternatives().stream()
					.map(SupportPlanSlotAlternativeEntity::resource).toList());
			var key = reference.resourceId() + ":" + reference.contentVersion();
			selections.put(reference.slotId(), candidates.stream().filter(candidate -> candidate.key().equals(key))
					.findFirst().orElseThrow());
		}
		Instant activatedAt = "ACTIVE".equals(outcome.resultingStatus()) ? outcome.resultingUpdatedAt() : null;
		return view(outcome.plan(), selections, outcome.resultingStatus(), outcome.resultingVersion(),
				outcome.resultingUpdatedAt(), activatedAt);
	}

	private SupportPlanView view(StoredPlan stored, Map<String, ResourceDraft> selections, String status,
			long version, Instant updatedAt, Instant activatedAt) {
		var plan = stored.plan();
		var families = stored.families().stream().map(family -> new TemplateFamilyView(family.family(),
				family.templateVersion(), family.targetDomain())).toList();
		var slots = stored.slots().stream().map(storedSlot -> {
			var selected = selections.get(storedSlot.slot().slotKey());
			var candidates = new ArrayList<ResourceDraft>();
			if (storedSlot.slot().selectedResource() != null) {
				candidates.add(storedSlot.slot().selectedResource());
			}
			candidates.addAll(storedSlot.alternatives().stream()
					.map(SupportPlanSlotAlternativeEntity::resource).toList());
			var alternatives = candidates.stream().filter(candidate -> selected == null || !candidate.key().equals(selected.key()))
					.collect(java.util.stream.Collectors.toMap(ResourceDraft::key, value -> value, (left, right) -> left,
							java.util.LinkedHashMap::new)).values().stream().map(this::resource).toList();
			return new SlotView(storedSlot.slot().slotKey(), storedSlot.slot().slotKind(),
					storedSlot.slot().targetDomain(), storedSlot.slot().purposeCode(), resource(selected), alternatives);
		}).toList();
		return new SupportPlanView(plan.id(), status, version,
				new SourceView(plan.supportEvaluationId(), 2, plan.evaluationPolicyVersion(), plan.evaluatedAt(),
						plan.selectionPolicyVersion(), plan.resourcePolicyVersion(), plan.resourcesResolvedAt()),
				new EntitlementView(plan.entitlementPackage(), plan.entitlementSource(),
						plan.entitlementPolicyVersion(), plan.entitlementVersion(), plan.entitlementDecidedAt()),
				new RationaleView(plan.rationaleCode(), plan.rationaleText()),
				new SafetyView(plan.safetyStatus(), plan.safetyReasonCode(), plan.safetyPolicyVersion(),
						plan.safetyGuidanceCode(), plan.safetyGuidance()),
				families, slots, selections.size(), plan.createdAt(), updatedAt, activatedAt,
				"WELLBEING_SUPPORT_NOT_TREATMENT", DISCLAIMER);
	}

	private ResourceView resource(ResourceDraft resource) {
		if (resource == null) {
			return null;
		}
		return new ResourceView(resource.resourceId(), Long.toString(resource.contentVersion()),
				resource.publicationId(), resource.role(), resource.category(), resource.title(), resource.summary(),
				resource.externalUrl());
	}

	private String hash(String canonical) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ApiException invalidChoice() {
		return new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_INVALID_CHOICE",
				"A requested resource version is not admitted for its SupportPlan slot");
	}

	private ApiException idempotencyConflict() {
		return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
				"Idempotency-Key was already used with a different SupportPlan command");
	}

	public record ProposeCommand(UUID sourceSupportEvaluationId) { }
	public record SlotSelection(String slotId, UUID resourceId, String contentVersion) { }
	public record ReplaceChoicesCommand(List<SlotSelection> slotSelections) { }
	public record ReplacePlanCommand(UUID currentSupportPlanId, long currentVersion) { }
	public record SourceView(UUID supportEvaluationId, int evaluationVersion, String evaluationPolicyVersion,
			Instant evaluatedAt, String selectionPolicyVersion, String resourceEligibilityPolicyVersion,
			Instant resourcesResolvedAt) { }
	public record EntitlementView(String packageCode, String source, String policyVersion, long version,
			Instant decidedAt) { }
	public record RationaleView(String code, String text) { }
	public record SafetyView(String status, String reasonCode, String policyVersion, String guidanceCode,
			String guidance) { }
	public record TemplateFamilyView(String family, int templateVersion, String targetDomain) { }
	public record ResourceView(UUID resourceId, String contentVersion, UUID publicationId, String role,
			String category, String title, String summary, String externalUrl) { }
	public record SlotView(String slotId, String kind, String targetDomain, String purposeCode,
			ResourceView selectedResource, List<ResourceView> allowedAlternatives) { }
	public record SupportPlanView(UUID supportPlanId, String status, long version, SourceView source,
			EntitlementView entitlement, RationaleView rationale, SafetyView safety,
			List<TemplateFamilyView> templateFamilies, List<SlotView> slots, int selectedResourceCount,
			Instant createdAt, Instant updatedAt, Instant activatedAt, String disclaimerCode, String disclaimer) { }
}
