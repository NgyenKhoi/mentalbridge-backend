package com.mentalbridge.care.supportplan;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.ConsultationResourceProposalHttpClient.ResourceProposal;

import feign.FeignException;

@Component
class ConsultationResourceProposalClient {

	private final ConsultationResourceProposalHttpClient http;

	ConsultationResourceProposalClient(ConsultationResourceProposalHttpClient http) {
		this.http = http;
	}

	ResourceProposal get(UUID proposalId, String bearerToken, UUID correlationId) {
		try {
			var proposal = http.response(proposalId, "Bearer " + bearerToken, correlationId.toString());
			if (!valid(proposalId, proposal)) throw unavailable();
			return proposal;
		}
		catch (FeignException.NotFound exception) {
			throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_PROPOSAL_NOT_FOUND",
					"The resource proposal was not found");
		}
		catch (FeignException.Conflict exception) {
			throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_PROPOSAL_STALE",
					"The resource proposal is no longer eligible for review");
		}
		catch (ApiException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	private boolean valid(UUID proposalId, ResourceProposal proposal) {
		if (proposal == null || !proposalId.equals(proposal.proposalId()) || proposal.version() < 1
				|| proposal.appointmentId() == null || proposal.userAccountId() == null
				|| proposal.specialistAccountId() == null || proposal.summaryId() == null
				|| proposal.summaryVersion() < 1 || proposal.completionFactId() == null
				|| proposal.resourceId() == null || proposal.resourceVersion() == null
				|| proposal.reasonCode() == null || proposal.title() == null
				|| proposal.summarySchemaVersion() == null || proposal.proposedAt() == null) return false;
		try {
			return Long.parseLong(proposal.resourceVersion()) >= 0;
		}
		catch (NumberFormatException exception) {
			return false;
		}
	}

	private ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RESOURCE_PROPOSAL_UNAVAILABLE",
				"The resource proposal could not be verified");
	}
}
