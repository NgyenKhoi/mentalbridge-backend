package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.ConsultationResourceProposalHttpClient.ResourceProposal;
import com.mentalbridge.care.supportplan.SupportPlanService.PreparedPlanChange;

@Service
class PlanChangeRequestWriter {

	private final PlanChangeRequestRepository requests;
	private final UserProfileRepository profiles;
	private final SupportPlanWriter plans;

	PlanChangeRequestWriter(PlanChangeRequestRepository requests, UserProfileRepository profiles,
			SupportPlanWriter plans) {
		this.requests = requests;
		this.profiles = profiles;
		this.plans = plans;
	}

	@Transactional
	PlanChangeRequestEntity create(UUID userId, ResourceProposal source, PreparedPlanChange prepared,
			String idempotencyKey, String requestHash, Instant now) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var replay = requests.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
				.or(() -> requests.findByUserIdAndSourceProposalId(userId, source.proposalId()));
		if (replay.isPresent()) {
			if (!requestHash.equals(replay.orElseThrow().requestHash())) throw idempotencyConflict();
			return replay.orElseThrow();
		}
		var current = prepared.current().plan();
		var previous = prepared.currentResource();
		return requests.saveAndFlush(new PlanChangeRequestEntity(UUID.randomUUID(), userId,
				source.specialistAccountId(), source.proposalId(), source.version(), source.appointmentId(),
				source.summaryId(), source.summaryVersion(), source.completionFactId(), source.reasonCode(),
				source.resourceId(), Long.parseLong(source.resourceVersion()), source.title(), source.details(),
				current.id(), current.version(), prepared.targetSlotId(),
				previous == null ? null : previous.resourceId(),
				previous == null ? null : previous.contentVersion(),
				previous == null ? null : previous.title(), idempotencyKey, requestHash, now));
	}

	@Transactional
	PlanChangeRequestEntity reject(UUID userId, UUID requestId, long expectedVersion,
			String idempotencyKey, String decisionHash, Instant now) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var globalReplay = decisionReplay(userId, requestId, idempotencyKey, decisionHash);
		if (globalReplay != null) return globalReplay;
		var request = locked(userId, requestId);
		var replay = decisionReplay(request, idempotencyKey, decisionHash);
		if (replay != null) return replay;
		if (request.version() != expectedVersion) throw versionMismatch();
		if (!"READY_FOR_REVIEW".equals(request.status())) throw terminalConflict();
		request.reject(idempotencyKey, decisionHash, now);
		return requests.saveAndFlush(request);
	}

	@Transactional
	PlanChangeRequestEntity accept(UUID userId, UUID requestId, long expectedVersion,
			String idempotencyKey, String decisionHash, PreparedPlanChange prepared,
			UUID correlationId, Instant now) {
		now = now.truncatedTo(ChronoUnit.MICROS);
		lockOwner(userId);
		var globalReplay = decisionReplay(userId, requestId, idempotencyKey, decisionHash);
		if (globalReplay != null) return globalReplay;
		var request = locked(userId, requestId);
		var replay = decisionReplay(request, idempotencyKey, decisionHash);
		if (replay != null) return replay;
		if (request.version() != expectedVersion) throw versionMismatch();
		if (!"READY_FOR_REVIEW".equals(request.status())) throw terminalConflict();
		if (!request.currentSupportPlanId().equals(prepared.current().plan().id())
				|| request.currentSupportPlanVersion() != prepared.current().plan().version()
				|| !request.targetSlotId().equals(prepared.targetSlotId())
				|| !request.resourceId().equals(prepared.proposedResource().resourceId())
				|| request.resourceVersion() != prepared.proposedResource().contentVersion()) {
			throw new ApiException(HttpStatus.CONFLICT, "PLAN_CHANGE_REQUEST_STALE",
					"The proposal no longer matches its reviewed SupportPlan context");
		}
		var replacement = plans.replaceFromPlanChange(userId, request.id(), request.currentSupportPlanId(),
				request.currentSupportPlanVersion(), idempotencyKey, decisionHash, prepared.evaluation(),
				prepared.entitlement(), prepared.proposal(), prepared.choices(), prepared.evidence(),
				correlationId, now);
		request.accept(idempotencyKey, decisionHash, replacement.plan().id(), replacement.plan().version(), now);
		return requests.saveAndFlush(request);
	}

	@Transactional(readOnly = true)
	PlanChangeRequestEntity userRequest(UUID userId, UUID requestId) {
		return requests.findByUserIdAndId(userId, requestId).orElseThrow(this::notFound);
	}

	@Transactional(readOnly = true)
	PlanChangeRequestEntity userProposal(UUID userId, UUID proposalId) {
		return requests.findByUserIdAndSourceProposalId(userId, proposalId).orElseThrow(this::notFound);
	}

	@Transactional(readOnly = true)
	PlanChangeRequestEntity specialistProposal(UUID specialistId, UUID proposalId) {
		return requests.findBySpecialistIdAndSourceProposalId(specialistId, proposalId).orElseThrow(this::notFound);
	}

	@Transactional(readOnly = true)
	PlanChangeRequestEntity replay(UUID userId, String idempotencyKey) {
		return requests.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElse(null);
	}

	private PlanChangeRequestEntity locked(UUID userId, UUID requestId) {
		return requests.findByIdAndUserIdForUpdate(requestId, userId).orElseThrow(this::notFound);
	}

	private PlanChangeRequestEntity decisionReplay(PlanChangeRequestEntity request,
			String idempotencyKey, String decisionHash) {
		if (request.decisionIdempotencyKey() == null) return null;
		if (idempotencyKey.equals(request.decisionIdempotencyKey())
				&& decisionHash.equals(request.decisionHash())) return request;
		throw idempotencyConflict();
	}

	private PlanChangeRequestEntity decisionReplay(UUID userId, UUID requestId,
			String idempotencyKey, String decisionHash) {
		var replay = requests.findByUserIdAndDecisionIdempotencyKey(userId, idempotencyKey).orElse(null);
		if (replay == null) return null;
		if (requestId.equals(replay.id()) && decisionHash.equals(replay.decisionHash())) return replay;
		throw idempotencyConflict();
	}

	private void lockOwner(UUID userId) {
		if (profiles.findByIdForUpdate(userId).isEmpty()) throw notFound();
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "PLAN_CHANGE_REQUEST_NOT_FOUND",
				"The plan change request was not found");
	}

	private ApiException versionMismatch() {
		return new ApiException(HttpStatus.PRECONDITION_FAILED, "PLAN_CHANGE_REQUEST_VERSION_MISMATCH",
				"The plan change request version does not match If-Match");
	}

	private ApiException terminalConflict() {
		return new ApiException(HttpStatus.CONFLICT, "PLAN_CHANGE_REQUEST_ALREADY_DECIDED",
				"The plan change request already has a final decision");
	}

	private ApiException idempotencyConflict() {
		return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
				"Idempotency-Key was already used with a different plan change request command");
	}
}
