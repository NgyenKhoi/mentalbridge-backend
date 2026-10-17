package com.mentalbridge.care.supportplan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.ConsultationResourceProposalHttpClient.ResourceProposal;

@Service
class PlanChangeRequestService {

	private final ConsultationResourceProposalClient proposals;
	private final SupportPlanService plans;
	private final PlanChangeRequestWriter writer;
	private final Clock clock;

	PlanChangeRequestService(ConsultationResourceProposalClient proposals, SupportPlanService plans,
			PlanChangeRequestWriter writer, Clock clock) {
		this.proposals = proposals;
		this.plans = plans;
		this.writer = writer;
		this.clock = clock;
	}

	PlanChangeRequestView create(UUID userId, String bearerToken, UUID proposalId,
			String idempotencyKey, UUID correlationId) {
		String requestHash = hash("CREATE\n" + proposalId);
		var replay = writer.replay(userId, idempotencyKey);
		if (replay != null) {
			if (!requestHash.equals(replay.requestHash())) throw idempotencyConflict();
			return view(replay);
		}
		var source = proposals.get(proposalId, bearerToken, correlationId);
		assertUser(source, userId);
		long resourceVersion = Long.parseLong(source.resourceVersion());
		var prepared = plans.preparePlanChange(userId, bearerToken, source.resourceId(), resourceVersion,
				null, null, correlationId);
		return view(writer.create(userId, source, prepared, idempotencyKey, requestHash, clock.instant()));
	}

	PlanChangeRequestView decide(UUID userId, String bearerToken, UUID requestId, long expectedVersion,
			String idempotencyKey, String decision, UUID correlationId) {
		String decisionHash = hash("DECIDE\n" + requestId + "\n" + decision);
		var current = writer.userRequest(userId, requestId);
		if (current.decisionIdempotencyKey() != null) {
			if (idempotencyKey.equals(current.decisionIdempotencyKey())
					&& decisionHash.equals(current.decisionHash())) return view(current);
			throw idempotencyConflict();
		}
		if ("REJECT".equals(decision)) {
			return view(writer.reject(userId, requestId, expectedVersion, idempotencyKey,
					decisionHash, clock.instant()));
		}
		if (!"ACCEPT".equals(decision)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "PLAN_CHANGE_DECISION_INVALID",
					"Decision must be ACCEPT or REJECT");
		}
		var source = proposals.get(current.sourceProposalId(), bearerToken, correlationId);
		assertUnchanged(current, source, userId);
		var prepared = plans.preparePlanChange(userId, bearerToken, source.resourceId(),
				Long.parseLong(source.resourceVersion()), current.currentSupportPlanId(),
				current.currentSupportPlanVersion(), correlationId);
		return view(writer.accept(userId, requestId, expectedVersion, idempotencyKey,
				decisionHash, prepared, correlationId, clock.instant()));
	}

	PlanChangeRequestView userProposal(UUID userId, UUID proposalId) {
		return view(writer.userProposal(userId, proposalId));
	}

	PlanChangeRequestView specialistProposal(UUID specialistId, UUID proposalId) {
		return view(writer.specialistProposal(specialistId, proposalId));
	}

	private void assertUser(ResourceProposal proposal, UUID userId) {
		if (!userId.equals(proposal.userAccountId())) throw new ApiException(HttpStatus.NOT_FOUND,
				"RESOURCE_PROPOSAL_NOT_FOUND", "The resource proposal was not found");
	}

	private void assertUnchanged(PlanChangeRequestEntity request, ResourceProposal source, UUID userId) {
		assertUser(source, userId);
		if (!request.sourceProposalId().equals(source.proposalId())
				|| request.sourceProposalVersion() != source.version()
				|| !request.sourceAppointmentId().equals(source.appointmentId())
				|| !request.specialistId().equals(source.specialistAccountId())
				|| !request.sourceSummaryId().equals(source.summaryId())
				|| request.sourceSummaryVersion() != source.summaryVersion()
				|| !request.sourceCompletionFactId().equals(source.completionFactId())
				|| !request.resourceId().equals(source.resourceId())
				|| request.resourceVersion() != Long.parseLong(source.resourceVersion())
				|| !request.proposalReasonCode().equals(source.reasonCode())) {
			throw new ApiException(HttpStatus.CONFLICT, "PLAN_CHANGE_REQUEST_STALE",
					"The specialist proposal changed after the Care review");
		}
	}

	private PlanChangeRequestView view(PlanChangeRequestEntity request) {
		var current = request.currentResourceId() == null ? null : new ResourceSnapshot(
				request.currentResourceId(), Long.toString(request.currentResourceVersion()),
				request.currentResourceTitle());
		var proposed = new ResourceSnapshot(request.resourceId(), Long.toString(request.resourceVersion()),
				request.proposalTitle());
		return new PlanChangeRequestView(request.id(), request.version(), request.status(), request.outcomeCode(),
				request.sourceProposalId(), request.sourceAppointmentId(), request.sourceSummaryId(),
				request.specialistId(), request.proposalReasonCode(), request.proposalDetails(),
				request.targetSlotId(), current, proposed, request.currentSupportPlanId(),
				request.currentSupportPlanVersion(), request.replacementSupportPlanId(),
				request.replacementSupportPlanVersion(), request.reviewedAt(), request.decidedAt(),
				request.createdAt(), request.updatedAt());
	}

	private String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ApiException idempotencyConflict() {
		return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
				"Idempotency-Key was already used with a different plan change request command");
	}

	record ResourceSnapshot(UUID resourceId, String resourceVersion, String title) { }
	record PlanChangeRequestView(UUID requestId, long version, String status, String outcomeCode,
			UUID sourceProposalId, UUID sourceAppointmentId, UUID sourceSummaryId, UUID specialistId,
			String proposalReasonCode, String proposalDetails, String targetSlotId,
			ResourceSnapshot currentResource, ResourceSnapshot proposedResource,
			UUID currentSupportPlanId, long currentSupportPlanVersion,
			UUID replacementSupportPlanId, Long replacementSupportPlanVersion,
			Instant reviewedAt, Instant decidedAt, Instant createdAt, Instant updatedAt) { }
}
