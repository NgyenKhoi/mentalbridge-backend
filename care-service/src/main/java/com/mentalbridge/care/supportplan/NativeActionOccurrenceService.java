package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.AuthoritativeOwnerFact;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.LinkedActionSource;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.OwnerFact;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.ActionOccurrenceView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.CompletionEvidenceView;

@Service
public class NativeActionOccurrenceService {
	private final SupportPlanRepository plans;
	private final PlanActionRepository actions;
	private final SupportPlanActivityOccurrenceRepository occurrences;
	private final SupportPlanActivityOccurrenceService completion;
	private final Clock clock;
	private final ObjectMapper mapper;

	public NativeActionOccurrenceService(SupportPlanRepository plans, PlanActionRepository actions,
			SupportPlanActivityOccurrenceRepository occurrences, SupportPlanActivityOccurrenceService completion,
			Clock clock, ObjectMapper mapper) {
		this.plans = plans;
		this.actions = actions;
		this.occurrences = occurrences;
		this.completion = completion;
		this.clock = clock;
		this.mapper = mapper;
	}

	@Transactional
	public ActionOccurrenceView change(UUID userId, UUID occurrenceId, long version, String state, UUID correlationId) {
		if (!List.of("SCHEDULED", "COMPLETED", "SKIPPED").contains(state)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "OCCURRENCE_STATE_INVALID", "Occurrence state is invalid");
		}
		var link = occurrences.findAuthorityLinkByIdAndUserId(occurrenceId, userId).orElseThrow(this::notFound);
		var plan = plans.findByIdAndUserIdForUpdate(link.getSupportPlanId(), userId).orElseThrow(this::notFound);
		if (plan.modelVersion() != 3 || link.getSourceActionId() == null) {
			throw conflict("SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED", "This command requires a native action occurrence");
		}
		var action = actions.findByIdAndUserIdForUpdate(link.getSourceActionId(), userId).orElseThrow(this::notFound);
		var occurrence = occurrences.findByIdAndUserIdForUpdate(occurrenceId, userId).orElseThrow(this::notFound);
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		if (state.equals(occurrence.state())) return view(occurrence, now);
		if (occurrence.version() != version) throw new ApiException(HttpStatus.PRECONDITION_FAILED,
				"OCCURRENCE_VERSION_MISMATCH", "Occurrence version does not match If-Match");
		if (!"ACTIVE".equals(plan.status()) || !"ACTIVE".equals(action.status())) {
			throw conflict("SUPPORT_PLAN_NOT_ACTIVE", "Only an active plan action accepts new user engagement");
		}
		if (occurrence.localDate().isAfter(now.atZone(ZoneId.of(occurrence.timezone())).toLocalDate())) {
			throw conflict("OCCURRENCE_NOT_DUE", "A future local-day occurrence cannot be acknowledged");
		}
		if ("COMPLETED".equals(occurrence.state()) || "CANCELLED".equals(occurrence.state()) || occurrence.evidenceId() != null) {
			throw conflict("OCCURRENCE_NOT_OPEN", "Accepted completion evidence and cancellations cannot be reopened");
		}
		if (!List.of("RESOURCE", "ROUTINE_SELF_MANAGEMENT", "EMOTION_CHECK_IN", "JOURNAL").contains(action.actionType())) {
			throw conflict("OCCURRENCE_STATE_NOT_SUPPORTED", "This action does not accept user occurrence acknowledgement");
		}
		if ("COMPLETED".equals(state)) {
			if (!List.of("RESOURCE", "ROUTINE_SELF_MANAGEMENT").contains(action.actionType())) {
				throw conflict("AUTHORITATIVE_COMPLETION_REQUIRED", "This action requires its source owner's completion fact");
			}
			if (!"SCHEDULED".equals(occurrence.state())) throw conflict("OCCURRENCE_NOT_OPEN", "Reopen a user skip before acknowledgement");
			var source = action.sourceId() == null ? null : new LinkedActionSource(action.sourceOwner(), action.sourceKind(),
					action.sourceId(), action.sourceRevision());
			String factId = UUID.nameUUIDFromBytes(("native-user-ack:" + userId + ":" + occurrenceId)
					.getBytes(StandardCharsets.UTF_8)).toString();
			completion.completeFromOwnerFact(userId, occurrenceId, new AuthoritativeOwnerFact(userId, source,
					new OwnerFact("CARE", "USER_ACKNOWLEDGEMENT", factId, "1", now, now)), correlationId);
			return view(occurrence, now);
		}
		occurrence.changeOpenNativeUserState(state, now);
		occurrence = occurrences.saveAndFlush(occurrence);
		appendStateEvent(occurrence, correlationId, now);
		return view(occurrence, now);
	}

	@Transactional
	public ActionOccurrenceView replaceEngagement(UUID userId, UUID occurrenceId, long version,
			String state, boolean hidden, String helpfulness, String barrierCode, String reflection,
			boolean summaryReuseApproved, UUID correlationId) {
		String normalizedReflection = reflection == null ? null : reflection.strip();
		SupportPlanActivityOccurrenceService.validateEngagement(state, helpfulness, barrierCode,
				normalizedReflection, summaryReuseApproved);
		return writeEngagement(userId, occurrenceId, version, state, hidden, helpfulness, barrierCode,
				normalizedReflection, summaryReuseApproved, correlationId);
	}

	@Transactional
	public ActionOccurrenceView deleteEngagement(UUID userId, UUID occurrenceId, long version, UUID correlationId) {
		return writeEngagement(userId, occurrenceId, version, null,
				false, null, null, null, false, correlationId);
	}

	private ActionOccurrenceView writeEngagement(UUID userId, UUID occurrenceId, long version, String state,
			boolean hidden, String helpfulness, String barrierCode, String reflection,
			boolean summaryReuseApproved, UUID correlationId) {
		var link = occurrences.findAuthorityLinkByIdAndUserId(occurrenceId, userId).orElseThrow(this::notFound);
		var plan = plans.findByIdAndUserIdForUpdate(link.getSupportPlanId(), userId).orElseThrow(this::notFound);
		if (plan.modelVersion() != 3 || link.getSourceActionId() == null) {
			throw conflict("SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED", "This command requires a native action occurrence");
		}
		var action = actions.findByIdAndUserIdForUpdate(link.getSourceActionId(), userId).orElseThrow(this::notFound);
		var occurrence = occurrences.findByIdAndUserIdForUpdate(occurrenceId, userId).orElseThrow(this::notFound);
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		if (occurrence.engagementEquals(state == null ? occurrence.state() : state, hidden, helpfulness, barrierCode,
				reflection, summaryReuseApproved)) return view(occurrence, now, true);
		if (state != null && !state.equals(occurrence.state())) {
			throw conflict("AUTHORITATIVE_COMPLETION_REQUIRED", "Engagement cannot change native occurrence completion state");
		}
		if (occurrence.version() != version) throw new ApiException(HttpStatus.PRECONDITION_FAILED,
				"OCCURRENCE_VERSION_MISMATCH", "Occurrence version does not match If-Match");
		if (!"ACTIVE".equals(plan.status()) || !"ACTIVE".equals(action.status())) {
			throw conflict("SUPPORT_PLAN_NOT_ACTIVE", "Only an active plan action accepts new user engagement");
		}
		if ("CANCELLED".equals(occurrence.state())) {
			throw conflict("OCCURRENCE_NOT_OPEN", "A cancelled occurrence does not accept engagement updates");
		}
		occurrence.replaceNativeEngagement(hidden, helpfulness, barrierCode, reflection, summaryReuseApproved, now);
		occurrence = occurrences.saveAndFlush(occurrence);
		appendStateEvent(occurrence, correlationId, now, true);
		return view(occurrence, now, true);
	}

	private ActionOccurrenceView view(SupportPlanActivityOccurrenceEntity value, Instant now) {
		return view(value, now, false);
	}

	private ActionOccurrenceView view(SupportPlanActivityOccurrenceEntity value, Instant now, boolean engagementEndpoint) {
		var fact = value.evidenceId() == null ? null : new CompletionEvidenceView(value.evidenceKind(), value.evidenceOwner(),
				value.evidenceId(), value.evidenceRevision(), value.evidenceOccurredAt(), value.evidenceVerifiedAt());
		boolean hasEngagement = engagementEndpoint || value.hasEngagement();
		return new ActionOccurrenceView(value.id(), value.sourceActionId().toString(), value.sourceActionType(), value.sourceActionVersion(),
				value.activityScheduleId(), value.scheduleVersion(), "NATIVE_V3", value.version(), value.localDate(), value.scheduledAt(),
				value.state(), "SCHEDULED".equals(value.state()) && value.scheduledAt().isBefore(now) ? "OVERDUE" : value.state(),
				value.stateReason(), fact, value.completedAt() != null ? value.completedAt() : value.skippedAt(),
				hasEngagement ? value.hidden() : null, hasEngagement ? value.helpfulness() : null,
				hasEngagement ? value.barrierCode() : null, hasEngagement ? value.reflection() : null,
				hasEngagement ? value.summaryReuseApproved() : null, hasEngagement ? value.engagementUpdatedAt() : null);
	}

	private void appendStateEvent(SupportPlanActivityOccurrenceEntity value, UUID correlation, Instant now) {
		appendStateEvent(value, correlation, now, false);
	}

	private void appendStateEvent(SupportPlanActivityOccurrenceEntity value, UUID correlation, Instant now,
			boolean engagementChanged) {
		var payload = mapper.createObjectNode();
		payload.put("supportPlanId", value.supportPlanId().toString()).put("userId", value.userId().toString())
				.put("planVersion", value.sourcePlanVersion()).put("actionId", value.sourceActionId().toString())
				.put("actionType", value.sourceActionType()).put("actionVersion", value.sourceActionVersion())
				.put("scheduleId", value.activityScheduleId().toString()).put("scheduleVersion", value.scheduleVersion())
				.put("occurrenceId", value.id().toString()).put("representationMode", "NATIVE_V3")
				.put("changeType", engagementChanged ? "ENGAGEMENT_CHANGED" : "STATE_CHANGED");
		payload.putObject("orchestration").put("state", value.state()).put("displayState", value.state())
				.put("stateReason", value.stateReason())
				.put("outcomeRecordedAt", value.completedAt() == null ? null : value.completedAt().toString())
				.put("interpretationCode", "SELF_REPORTED_WELLBEING_ACTIVITY_NOT_TREATMENT_ADHERENCE");
		if (value.evidenceId() == null) payload.putNull("ownerFact");
		else payload.putObject("ownerFact").put("ownerDomain", value.evidenceOwner()).put("kind", value.evidenceKind())
				.put("factId", value.evidenceId()).put("factRevision", value.evidenceRevision())
				.put("occurredAt", value.evidenceOccurredAt().toString()).put("verifiedAt", value.evidenceVerifiedAt().toString());
		if (engagementChanged) payload.putObject("engagement").put("hidden", value.hidden())
				.put("helpfulness", value.helpfulness()).put("barrierCode", value.barrierCode())
				.put("summaryReuseApproved", value.summaryReuseApproved()).put("updatedAt", value.engagementUpdatedAt().toString());
		try {
			occurrences.insertActionOccurrenceOutbox(UUID.randomUUID(), value.id(), value.version(),
					correlation == null ? UUID.randomUUID() : correlation, mapper.writeValueAsString(payload), now);
		}
		catch (JsonProcessingException exception) { throw new IllegalStateException("Native occurrence state fact could not be serialized", exception); }
	}
	private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "OCCURRENCE_NOT_FOUND", "SupportPlan occurrence was not found"); }
	private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
