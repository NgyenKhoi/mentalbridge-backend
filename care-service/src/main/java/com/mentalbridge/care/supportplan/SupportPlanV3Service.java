package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.shared.ApiException;

@Service
public class SupportPlanV3Service {

	private static final String REPRESENTATION_V2 = "V2_RESOURCE_ADAPTER";
	private static final String REPRESENTATION_V3 = "NATIVE_V3";
	private static final String INTERPRETATION = "SELF_REPORTED_WELLBEING_ACTIVITY_NOT_TREATMENT_ADHERENCE";
	private static final String SCHEDULE_POLICY_V1 = "support-plan-activity-schedule-v1";

	private final SupportPlanRepository plans;
	private final SupportPlanTemplateFamilyRepository families;
	private final SupportPlanSlotRepository slots;
	private final SupportPlanFocusRepository focuses;
	private final PlanActionRepository actions;
	private final SupportPlanActivityScheduleRepository schedules;
	private final SupportPlanActivityOccurrenceService activities;
	private final Clock clock;

	public SupportPlanV3Service(SupportPlanRepository plans, SupportPlanTemplateFamilyRepository families,
			SupportPlanSlotRepository slots,
			SupportPlanFocusRepository focuses, PlanActionRepository actions,
			SupportPlanActivityScheduleRepository schedules,
			SupportPlanActivityOccurrenceService activities, Clock clock) {
		this.plans = plans;
		this.families = families;
		this.slots = slots;
		this.focuses = focuses;
		this.actions = actions;
		this.schedules = schedules;
		this.activities = activities;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public SupportPlanV3View current(UUID userId) {
		var plan = plans.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "PAUSED")).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_CURRENT_NOT_FOUND",
						"Current SupportPlan was not found"));
		return view(plan);
	}

	@Transactional(readOnly = true)
	public SupportPlanV3View get(UUID userId, UUID supportPlanId) {
		var plan = plans.findByIdAndUserId(supportPlanId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_PLAN_NOT_FOUND", "SupportPlan was not found"));
		return view(plan);
	}

	@Transactional
	public ActionOccurrenceListView occurrences(UUID userId, LocalDate from, LocalDate through) {
		var window = activities.loadV3Window(userId, from, through);
		var plan = window.plan();
		String representation = representation(plan);
		Map<String, UUID> legacyActions = new java.util.HashMap<>();
		if (plan.modelVersion() == 2) {
			for (var slot : slots.findBySupportPlanIdOrderByOrdinal(plan.id())) {
				legacyActions.put(slot.slotKey(), slot.id());
			}
		}
		Instant now = clock.instant();
		var values = window.occurrences().stream().map(value -> {
			boolean nativeAction = value.sourceActionId() != null;
			UUID actionId = nativeAction ? value.sourceActionId() : legacyActions.get(value.sourceSlotKey());
			if (actionId == null) {
				throw invalidModel();
			}
			String displayState = "SCHEDULED".equals(value.state()) && value.scheduledAt().isBefore(now)
					? "OVERDUE" : value.state();
			CompletionEvidenceView evidence = value.evidenceKind() == null ? null
					: new CompletionEvidenceView(value.evidenceKind(), value.evidenceOwner(), value.evidenceId(),
							value.evidenceRevision(), value.evidenceOccurredAt(), value.evidenceVerifiedAt());
			Instant outcomeRecordedAt = switch (value.state()) {
				case "COMPLETED" -> value.completedAt();
				case "SKIPPED" -> value.skippedAt();
				case "CANCELLED" -> value.cancelledAt();
				default -> null;
			};
			return new ActionOccurrenceView(value.id(), actionId.toString(),
					nativeAction ? value.sourceActionType() : "RESOURCE",
					nativeAction ? value.sourceActionVersion() : value.sourcePlanVersion(),
					value.activityScheduleId(), value.scheduleVersion(), representation, value.version(),
					value.localDate(), value.scheduledAt(), value.state(), displayState, value.stateReason(),
					evidence, outcomeRecordedAt);
		}).toList();
		return new ActionOccurrenceListView(plan.id(), plan.status(), representation, from, through, values,
				INTERPRETATION);
	}

	private SupportPlanV3View view(SupportPlanEntity plan) {
		var values = plan.modelVersion() == 2 ? adaptedFocuses(plan) : nativeFocuses(plan);
		if (values.isEmpty()) {
			throw invalidModel();
		}
		String policyVersion = plan.modelVersion() == 2 ? plan.selectionPolicyVersion() : plan.planPolicyVersion();
		var safety = new SafetyView(plan.safetyStatus(), plan.safetyReasonCode(), plan.safetyPolicyVersion(),
				plan.safetyGuidanceCode(), plan.safetyGuidance());
		return new SupportPlanV3View(plan.id(), 3, policyVersion, representation(plan), plan.status(), plan.version(), safety, values,
				plan.createdAt(), plan.updatedAt(), plan.activatedAt(), plan.completedAt(), plan.completionReason(),
				plan.supersededAt(), plan.discardedAt(), "WELLBEING_SUPPORT_NOT_TREATMENT",
				SupportPlanService.disclaimer());
	}

	private List<PlanFocusView> adaptedFocuses(SupportPlanEntity plan) {
		var scheduleBySlot = new java.util.HashMap<String, List<SupportPlanActivityScheduleEntity>>();
		for (var schedule : schedules.findBySupportPlanIdOrderByOrdinal(plan.id())) {
			scheduleBySlot.computeIfAbsent(schedule.sourceSlotKey(), ignored -> new ArrayList<>()).add(schedule);
		}
		var grouped = new LinkedHashMap<String, List<SupportPlanSlotEntity>>();
		var professionalDomains = new java.util.HashSet<String>();
		for (var family : families.findBySupportPlanIdOrderByOrdinal(plan.id())) {
			if (family.family().endsWith("_PROFESSIONAL_ADJUNCT")) {
				professionalDomains.add(family.targetDomain());
				grouped.putIfAbsent(family.targetDomain(), new ArrayList<>());
			}
		}
		for (var slot : slots.findBySupportPlanIdOrderByOrdinal(plan.id())) {
			if (slot.selectedResource() != null) {
				grouped.computeIfAbsent(slot.targetDomain(), ignored -> new ArrayList<>()).add(slot);
			}
		}
		var result = new ArrayList<PlanFocusView>();
		int focusOrdinal = 1;
		for (var entry : grouped.entrySet()) {
			var actionViews = new ArrayList<PlanActionView>();
			if (professionalDomains.contains(entry.getKey())) {
				actionViews.add(adaptedProfessionalSupport(plan, entry.getKey()));
			}
			for (var slot : entry.getValue()) {
				actionViews.add(adaptedAction(plan, slot,
						scheduleBySlot.getOrDefault(slot.slotKey(), List.of()),
						professionalDomains.contains(entry.getKey()), actionViews.size() + 1));
			}
			result.add(new PlanFocusView(virtualFocusId(plan.id(), entry.getKey()), focusTitle(entry.getKey()),
					plan.rationaleText(), focusOrdinal++, List.copyOf(actionViews)));
		}
		return result;
	}

	private PlanActionView adaptedAction(SupportPlanEntity plan, SupportPlanSlotEntity slot,
			List<SupportPlanActivityScheduleEntity> actionSchedules, boolean adjunct, int ordinal) {
		var resource = slot.selectedResource();
		var source = new ActionSourceReferenceView("CONTENT", "RESOURCE_VERSION",
				resource.resourceId().toString(), Long.toString(resource.contentVersion()), null,
				resource.publicationId(), plan.resourcePolicyVersion(), plan.resourcesResolvedAt());
		var provenance = new ActionProvenanceView("SYSTEM_POLICY", "SUPPORT_PLAN_SELECTION",
				plan.selectionPolicyVersion(), plan.createdAt());
		var presentation = new ActionPresentationMetadataView(resource.title(), resource.summary(), null,
				resource.externalUrl());
		return new PlanActionView(slot.id().toString(), "RESOURCE", plan.version(), REPRESENTATION_V2,
				actionStatus(plan.status()), adjunct ? "ADJUNCT" : "PRIMARY", slot.purposeCode(),
				plan.rationaleText(), ordinal, source,
				provenance, presentation, actionSchedules.stream().map(this::schedule).toList(), plan.createdAt(),
				plan.updatedAt(), plan.activatedAt(), actionEndedAt(plan));
	}

	private PlanActionView adaptedProfessionalSupport(SupportPlanEntity plan, String targetDomain) {
		var source = new ActionSourceReferenceView("CONSULTATION", "PROFESSIONAL_SUPPORT_PATHWAY",
				"approved-online-specialist-discovery", "1", "support-plan-professional-path-v1",
				null, null, null);
		var provenance = new ActionProvenanceView("SYSTEM_POLICY", plan.supportEvaluationId().toString(),
				plan.evaluationPolicyVersion(), plan.createdAt());
		var presentation = new ActionPresentationMetadataView("Kết nối với chuyên gia",
				"Đây là hướng hỗ trợ chính. Bạn có thể tự xem chuyên gia phù hợp và chỉ đặt lịch khi đã xác nhận.",
				"Xem hồ sơ và khung giờ trước khi chủ động gửi yêu cầu đặt lịch. Kế hoạch không tự đặt lịch hoặc chia sẻ dữ liệu.",
				null);
		return new PlanActionView(virtualProfessionalActionId(plan.id(), targetDomain), "PROFESSIONAL_SUPPORT",
				plan.version(), REPRESENTATION_V2, actionStatus(plan.status()), "PRIMARY",
				"CONNECT_WITH_PROFESSIONAL_SUPPORT", plan.rationaleText(), 1, source, provenance, presentation,
				List.of(), plan.createdAt(), plan.updatedAt(), plan.activatedAt(), actionEndedAt(plan));
	}

	private List<PlanFocusView> nativeFocuses(SupportPlanEntity plan) {
		var actionByFocus = new java.util.HashMap<UUID, List<PlanActionEntity>>();
		for (var action : actions.findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(plan.id(), plan.userId())) {
			actionByFocus.computeIfAbsent(action.planFocusId(), ignored -> new ArrayList<>()).add(action);
		}
		var scheduleByAction = new java.util.HashMap<UUID, List<SupportPlanActivityScheduleEntity>>();
		for (var schedule : schedules.findBySupportPlanIdOrderByOrdinal(plan.id())) {
			if (schedule.sourceActionId() != null) {
				scheduleByAction.computeIfAbsent(schedule.sourceActionId(), ignored -> new ArrayList<>()).add(schedule);
			}
		}
		var result = new ArrayList<PlanFocusView>();
		for (var focus : focuses.findBySupportPlanIdAndUserIdOrderByOrdinal(plan.id(), plan.userId())) {
			var focusActions = actionByFocus.getOrDefault(focus.id(), List.of());
			boolean professionalSupportPrimary = focusActions.stream().anyMatch(action ->
					"PROFESSIONAL_SUPPORT".equals(action.actionType()) && !"ENDED".equals(action.status()));
			var actionViews = focusActions.stream()
					.map(action -> nativeAction(action, scheduleByAction.getOrDefault(action.id(), List.of()),
							professionalSupportPrimary))
					.toList();
			if (!actionViews.isEmpty()) {
				result.add(new PlanFocusView(focus.id().toString(), focus.title(), focus.rationale(),
						focus.ordinal(), actionViews));
			}
		}
		return result;
	}

	private PlanActionView nativeAction(PlanActionEntity action,
			List<SupportPlanActivityScheduleEntity> actionSchedules, boolean professionalSupportPrimary) {
		ActionSourceReferenceView source = action.sourceOwner() == null ? null : new ActionSourceReferenceView(
				action.sourceOwner(), action.sourceKind(), action.sourceId(), action.sourceRevision(),
				action.sourcePolicyVersion(), action.sourcePublicationId(), action.sourceEligibilityPolicyVersion(),
				action.sourceVerifiedAt());
		var provenance = new ActionProvenanceView(action.provenanceType(), action.provenanceReferenceId(),
				action.provenanceReferenceVersion(), action.provenanceAcceptedAt());
		var presentation = new ActionPresentationMetadataView(action.presentationTitle(),
				action.presentationSummary(), action.presentationInstructions(), action.presentationExternalUrl());
		return new PlanActionView(action.id().toString(), action.actionType(), action.version(), REPRESENTATION_V3,
				action.status(), nativePathRole(action, professionalSupportPrimary), action.purposeCode(),
				action.rationale(), action.ordinal(), source, provenance,
				presentation, actionSchedules.stream().map(this::schedule).toList(), action.createdAt(),
				action.updatedAt(), action.activatedAt(), action.endedAt());
	}

	private String nativePathRole(PlanActionEntity action, boolean professionalSupportPrimary) {
		if (!professionalSupportPrimary) return "PRIMARY";
		if ("PROFESSIONAL_SUPPORT".equals(action.actionType()) && !"ENDED".equals(action.status())) return "PRIMARY";
		return "ADJUNCT";
	}

	private ActionScheduleView schedule(SupportPlanActivityScheduleEntity schedule) {
		return new ActionScheduleView(schedule.id(), schedule.scheduleVersion(),
				schedule.schedulePolicyVersion() == null ? SCHEDULE_POLICY_V1 : schedule.schedulePolicyVersion(),
				schedule.recurrenceType(), schedule.recurrenceDayOfWeek(), schedule.localTime(), schedule.timezone(),
				schedule.effectiveFrom(), schedule.effectiveUntil(), schedule.reminderEnabled(), schedule.status());
	}

	private String representation(SupportPlanEntity plan) {
		return plan.modelVersion() == 2 ? REPRESENTATION_V2 : REPRESENTATION_V3;
	}

	private String virtualFocusId(UUID planId, String targetDomain) {
		return UUID.nameUUIDFromBytes((REPRESENTATION_V2 + ":" + planId + ":" + targetDomain)
				.getBytes(StandardCharsets.UTF_8)).toString();
	}

	private String virtualProfessionalActionId(UUID planId, String targetDomain) {
		return UUID.nameUUIDFromBytes((REPRESENTATION_V2 + ":" + planId + ":" + targetDomain
				+ ":PROFESSIONAL_SUPPORT").getBytes(StandardCharsets.UTF_8)).toString();
	}

	private String focusTitle(String targetDomain) {
		return "ANXIETY_SYMPTOMS".equals(targetDomain) ? "Hỗ trợ bình tâm" : "Hỗ trợ tâm trạng";
	}

	private String actionStatus(String planStatus) {
		if ("DRAFT".equals(planStatus)) return "DRAFT";
		if (List.of("ACTIVE", "PAUSED").contains(planStatus)) return "ACTIVE";
		return "ENDED";
	}

	private Instant actionEndedAt(SupportPlanEntity plan) {
		if (plan.completedAt() != null) return plan.completedAt();
		if (plan.supersededAt() != null) return plan.supersededAt();
		return plan.discardedAt();
	}

	private ApiException invalidModel() {
		return new ApiException(HttpStatus.CONFLICT, "SUPPORT_PLAN_MODEL_INVALID",
				"SupportPlan action model is incomplete or inconsistent");
	}

	public record SupportPlanV3View(UUID supportPlanId, int modelVersion, String planPolicyVersion, String representationMode,
			String status, long version, SafetyView safety, List<PlanFocusView> focuses, Instant createdAt, Instant updatedAt,
			Instant activatedAt, Instant completedAt, String completionReason, Instant supersededAt,
			Instant discardedAt, String disclaimerCode, String disclaimer) { }
	public record SafetyView(String status, String reasonCode, String policyVersion,
			String guidanceCode, String guidance) { }
	public record PlanFocusView(String focusId, String title, String rationale, int ordinal,
			List<PlanActionView> actions) { }
	public record PlanActionView(String actionId, String actionType, long actionVersion,
			String representationMode, String status, String pathRole, String purposeCode, String rationale, int ordinal,
			ActionSourceReferenceView source, ActionProvenanceView provenance,
			ActionPresentationMetadataView presentation, List<ActionScheduleView> schedules,
			Instant createdAt, Instant updatedAt, Instant activatedAt, Instant endedAt) { }
	public record ActionSourceReferenceView(String ownerDomain, String sourceKind, String sourceId,
			String sourceRevision, String sourcePolicyVersion, UUID publicationId,
			String eligibilityPolicyVersion, Instant verifiedAt) { }
	public record ActionProvenanceView(String category, String referenceId, String referenceVersion,
			Instant acceptedAt) { }
	public record ActionPresentationMetadataView(String title, String summary, String instructions,
			String externalUrl) { }
	public record ActionScheduleView(UUID scheduleId, int version, String schedulePolicyVersion,
			String recurrenceType, Short recurrenceDayOfWeek, LocalTime localTime, String timezone,
			LocalDate effectiveFrom, LocalDate effectiveUntil, boolean reminderEnabled, String status) { }
	public record ActionOccurrenceListView(UUID supportPlanId, String supportPlanStatus,
			String representationMode, LocalDate from, LocalDate through,
			List<ActionOccurrenceView> occurrences, String interpretationCode) { }
	public record ActionOccurrenceView(UUID occurrenceId, String actionId, String actionType,
			long actionVersion, UUID scheduleId, int scheduleVersion, String representationMode,
			long version, LocalDate localDate, Instant scheduledAt, String state, String displayState,
			String stateReason, CompletionEvidenceView completionEvidence, Instant outcomeRecordedAt) { }
	public record CompletionEvidenceView(String kind, String ownerDomain, String factId,
			String factRevision, Instant occurredAt, Instant verifiedAt) { }
}
