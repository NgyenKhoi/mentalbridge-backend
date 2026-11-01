package com.mentalbridge.care.supportplan;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.ResourceEvidenceHttpClient.Evidence;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.AuthoritativeOwnerFact;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.LinkedActionSource;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.OwnerFact;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionOccurrenceView;

@Component
public class ResourceReconciliationWriter {
	private final SupportPlanRepository plans;
	private final PlanActionRepository actions;
	private final SupportPlanActivityOccurrenceRepository occurrences;
	private final SupportPlanActivityOccurrenceService completion;
	private final NativeActionOccurrenceService views;
	private final Clock clock;

	public ResourceReconciliationWriter(SupportPlanRepository plans, PlanActionRepository actions,
			SupportPlanActivityOccurrenceRepository occurrences, SupportPlanActivityOccurrenceService completion,
			NativeActionOccurrenceService views, Clock clock) {
		this.plans = plans;
		this.actions = actions;
		this.occurrences = occurrences;
		this.completion = completion;
		this.views = views;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Context context(UUID userId, UUID occurrenceId, long version) {
		var occurrence = occurrences.findByIdAndUserId(occurrenceId, userId).orElseThrow(this::notFound);
		var plan = plans.findByIdAndUserId(occurrence.supportPlanId(), userId).orElseThrow(this::notFound);
		var action = occurrence.sourceActionId() == null ? null : actions.findById(occurrence.sourceActionId())
				.filter(value -> userId.equals(value.userId())).orElseThrow(this::notFound);
		validateTarget(plan, action, occurrence);
		if (!accepted(occurrence) && occurrence.version() != version) throw stale();
		return context(plan, occurrence, action, accepted(occurrence) ? views.view(occurrence, clock.instant()) : null);
	}

	@Transactional(readOnly = true)
	public List<String> usedFactIds(UUID userId, Context context) {
		return occurrences.findUsedResourceFactIds(userId, context.source().sourceId(), context.source().sourceRevision(),
				context.occurrenceId());
	}

	@Transactional
	public ActionOccurrenceView accept(UUID userId, Context expected, Evidence evidence, UUID correlationId) {
		var plan = plans.findByIdAndUserIdForUpdate(expected.supportPlanId(), userId).orElseThrow(this::notFound);
		var action = actions.findByIdAndUserIdForUpdate(expected.actionId(), userId).orElseThrow(this::notFound);
		var occurrence = occurrences.findByIdAndUserIdForUpdate(expected.occurrenceId(), userId).orElseThrow(this::notFound);
		validateTarget(plan, action, occurrence);
		if (accepted(occurrence)) return views.view(occurrence, clock.instant());
		if (!expected.equals(context(plan, occurrence, action, null))) throw stale();
		if (evidence == null || !expected.source().sourceRevision().equals(evidence.resourceVersion())
				|| evidence.occurredAt() == null || evidence.verifiedAt() == null
				|| !evidence.occurredAt().atZone(ZoneId.of(expected.timeZone())).toLocalDate().equals(expected.localDate())
				|| action.activatedAt() == null || evidence.occurredAt().isBefore(action.activatedAt())
				|| plan.activatedAt() == null || evidence.occurredAt().isBefore(plan.activatedAt())) {
			throw conflict("RESOURCE_EVIDENCE_NOT_FOUND", "No matching resource completion fact was found");
		}
		try {
			completion.completeFromOwnerFact(userId, occurrence.id(), new AuthoritativeOwnerFact(userId,
					expected.source(), new OwnerFact("CONTENT", evidence.factKind(), evidence.factId(),
							evidence.resourceVersion(), evidence.occurredAt(), evidence.verifiedAt())), correlationId);
		}
		catch (ApiException exception) {
			if ("COMPLETION_EVIDENCE_ALREADY_USED".equals(exception.code())) {
				throw conflict("RESOURCE_EVIDENCE_ALREADY_USED", "The resource fact already completes another occurrence");
			}
			throw exception;
		}
		return views.view(occurrence, clock.instant());
	}

	private Context context(SupportPlanEntity plan, SupportPlanActivityOccurrenceEntity occurrence, PlanActionEntity action,
			ActionOccurrenceView replay) {
		return new Context(occurrence.id(), occurrence.supportPlanId(), action.id(), action.version(), occurrence.version(),
				occurrence.localDate(), occurrence.timezone(), new LinkedActionSource(action.sourceOwner(),
						action.sourceKind(), action.sourceId(), action.sourceRevision()), replay);
	}

	private boolean accepted(SupportPlanActivityOccurrenceEntity occurrence) {
		return "COMPLETED".equals(occurrence.state()) && "CONTENT".equals(occurrence.evidenceOwner());
	}

	private void validateTarget(SupportPlanEntity plan, PlanActionEntity action,
			SupportPlanActivityOccurrenceEntity occurrence) {
		if (plan.modelVersion() != 3 || action == null || !"RESOURCE".equals(action.actionType())
				|| !"RESOURCE".equals(occurrence.sourceActionType())
				|| !Objects.equals(plan.id(), action.supportPlanId())
				|| !Objects.equals(action.id(), occurrence.sourceActionId())
				|| !"CONTENT".equals(action.sourceOwner()) || !"RESOURCE_VERSION".equals(action.sourceKind())) {
			throw conflict("RESOURCE_ACTION_REQUIRED", "A native resource action is required");
		}
		if (accepted(occurrence)) return;
		if (!"ACTIVE".equals(plan.status()) || !"ACTIVE".equals(action.status())) {
			throw conflict("SUPPORT_PLAN_NOT_ACTIVE", "Only an active plan action accepts new completion");
		}
		if (!"SCHEDULED".equals(occurrence.state())) {
			throw conflict("OCCURRENCE_NOT_OPEN", "Only an open occurrence accepts resource reconciliation");
		}
		if (occurrence.localDate().isAfter(clock.instant().atZone(ZoneId.of(occurrence.timezone())).toLocalDate())) {
			throw conflict("OCCURRENCE_NOT_OPEN", "A future local day cannot be completed");
		}
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "OCCURRENCE_NOT_FOUND", "SupportPlan occurrence was not found");
	}
	private ApiException stale() {
		return new ApiException(HttpStatus.PRECONDITION_FAILED, "OCCURRENCE_VERSION_MISMATCH", "Occurrence version does not match If-Match");
	}
	private ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message);
	}
	public record Context(UUID occurrenceId, UUID supportPlanId, UUID actionId, long actionVersion,
			long occurrenceVersion, LocalDate localDate, String timeZone, LinkedActionSource source, ActionOccurrenceView replay) { }
}
