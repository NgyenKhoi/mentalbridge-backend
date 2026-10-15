package com.mentalbridge.care.supportplan;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.JournalEntryEvidenceHttpClient.Evidence;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.AuthoritativeOwnerFact;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.LinkedActionSource;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.OwnerFact;

@Component
public class JournalEntryReconciliationWriter {
	private final SupportPlanRepository plans;
	private final PlanActionRepository actions;
	private final SupportPlanActivityOccurrenceRepository occurrences;
	private final SupportPlanActivityOccurrenceService completion;
	private final Clock clock;

	public JournalEntryReconciliationWriter(SupportPlanRepository plans, PlanActionRepository actions,
			SupportPlanActivityOccurrenceRepository occurrences,
			SupportPlanActivityOccurrenceService completion, Clock clock) {
		this.plans = plans;
		this.actions = actions;
		this.occurrences = occurrences;
		this.completion = completion;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Context context(UUID userId, UUID occurrenceId) {
		var occurrence = occurrences.findByIdAndUserId(occurrenceId, userId).orElseThrow(this::notFound);
		var plan = plans.findByIdAndUserId(occurrence.supportPlanId(), userId).orElseThrow(this::notFound);
		var action = occurrence.sourceActionId() == null ? null
				: actions.findById(occurrence.sourceActionId()).filter(value -> userId.equals(value.userId()))
						.orElseThrow(this::notFound);
		validateTarget(plan, action, occurrence);
		return context(occurrence, action);
	}

	@Transactional
	public Reconciliation accept(UUID userId, Context expected, UUID journalId, Evidence evidence, UUID correlationId) {
		var plan = plans.findByIdAndUserIdForUpdate(expected.supportPlanId(), userId).orElseThrow(this::notFound);
		var action = actions.findByIdAndUserIdForUpdate(expected.actionId(), userId).orElseThrow(this::notFound);
		var occurrence = occurrences.findByIdAndUserIdForUpdate(expected.occurrenceId(), userId)
				.orElseThrow(this::notFound);
		validateTarget(plan, action, occurrence);
		if (occurrence.evidenceId() != null && !journalId.toString().equals(occurrence.evidenceId())) {
			throw conflict("JOURNAL_ENTRY_EVIDENCE_MISMATCH", "This occurrence already has a different writing fact");
		}
		if (!expected.equals(context(occurrence, action))) {
			throw conflict("JOURNAL_ENTRY_RECONCILIATION_STALE", "The action context changed; reload and retry");
		}
		if (evidence == null || !userId.equals(evidence.subjectUserId())
				|| !journalId.equals(evidence.journalId())) {
			throw conflict("JOURNAL_ENTRY_EVIDENCE_MISMATCH", "Journal evidence does not match the occurrence");
		}
		if ("RECORDED".equals(evidence.state())) {
			var fact = evidence.fact();
			if (fact == null || !journalId.equals(fact.factId())
					|| !fact.occurredAt().atZone(ZoneId.of(expected.timezone())).toLocalDate()
							.equals(expected.localDate())
					|| action.activatedAt() == null || fact.occurredAt().isBefore(action.activatedAt())
					|| plan.activatedAt() == null || fact.occurredAt().isBefore(plan.activatedAt())) {
				throw conflict("JOURNAL_ENTRY_EVIDENCE_MISMATCH",
						"The writing date or activation context does not match this occurrence");
			}
			var accepted = completion.completeFromOwnerFact(userId, occurrence.id(),
					new AuthoritativeOwnerFact(userId, expected.source(),
							new OwnerFact(fact.ownerDomain(), fact.kind(), fact.factId().toString(),
									fact.factRevision(), fact.occurredAt(), evidence.verifiedAt())), correlationId);
			return new Reconciliation("journal-entry-occurrence-reconciliation-v1", occurrence.id(),
					action.id(), occurrence.localDate(), journalId, evidence.state(), accepted.state(), accepted.version(),
					accepted.completedAt(), accepted.ownerFact());
		}
		if (!"MISSING".equals(evidence.state()) && !"DELETED".equals(evidence.state()) || evidence.fact() != null) {
			throw conflict("JOURNAL_ENTRY_EVIDENCE_MISMATCH", "Journal evidence state is invalid");
		}
		OwnerFact accepted = occurrence.evidenceId() == null ? null
				: new OwnerFact(occurrence.evidenceOwner(), occurrence.evidenceKind(), occurrence.evidenceId(),
						occurrence.evidenceRevision(), occurrence.evidenceOccurredAt(), occurrence.evidenceVerifiedAt());
		return new Reconciliation("journal-entry-occurrence-reconciliation-v1", occurrence.id(), action.id(),
				occurrence.localDate(), journalId, evidence.state(), occurrence.state(), occurrence.version(),
				occurrence.completedAt(), accepted);
	}

	private Context context(SupportPlanActivityOccurrenceEntity occurrence, PlanActionEntity action) {
		var source = action.sourceId() == null ? null : new LinkedActionSource(action.sourceOwner(),
				action.sourceKind(), action.sourceId(), action.sourceRevision());
		return new Context(occurrence.id(), occurrence.supportPlanId(), action.id(), action.version(),
				occurrence.localDate(), occurrence.timezone(), source);
	}

	private void validateTarget(SupportPlanEntity plan, PlanActionEntity action,
			SupportPlanActivityOccurrenceEntity occurrence) {
		if (plan.modelVersion() != 3 || action == null || !"JOURNAL".equals(action.actionType())
				|| !"JOURNAL".equals(occurrence.sourceActionType())
				|| !Objects.equals(plan.id(), action.supportPlanId())
				|| !Objects.equals(plan.id(), occurrence.supportPlanId())
				|| !Objects.equals(action.id(), occurrence.sourceActionId())
				|| action.sourceId() != null && (!"JOURNAL_AI".equals(action.sourceOwner())
						|| !"JOURNAL_TEMPLATE".equals(action.sourceKind()))) {
			throw conflict("JOURNAL_ACTION_REQUIRED", "A native Journal action is required");
		}
		if (!"SCHEDULED".equals(occurrence.state()) && !"COMPLETED".equals(occurrence.state())) {
			throw conflict("OCCURRENCE_NOT_OPEN", "This occurrence does not accept Journal reconciliation");
		}
		if ("SCHEDULED".equals(occurrence.state()) && (!"ACTIVE".equals(plan.status())
				|| !"ACTIVE".equals(action.status()))) {
			throw conflict("SUPPORT_PLAN_NOT_ACTIVE", "Only an active plan action accepts new completion");
		}
		if (occurrence.localDate().isAfter(clock.instant().atZone(ZoneId.of(occurrence.timezone())).toLocalDate())) {
			throw conflict("JOURNAL_OCCURRENCE_NOT_DUE", "A future local day cannot be completed");
		}
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "OCCURRENCE_NOT_FOUND", "SupportPlan occurrence was not found");
	}

	private ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message);
	}

	public record Context(UUID occurrenceId, UUID supportPlanId, UUID actionId, long actionVersion,
			LocalDate localDate, String timezone, LinkedActionSource source) { }
	public record Reconciliation(String schemaVersion, UUID occurrenceId, UUID actionId, LocalDate localDate,
			UUID journalId, String sourceState, String occurrenceState, long version, Instant completedAt,
			OwnerFact completionEvidence) { }
}
