package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import com.mentalbridge.care.entitlement.EntitlementFeignConfiguration;

@FeignClient(name = "consultation-service", contextId = "careResourceProposalClient",
		url = "${mentalbridge.care.entitlement.base-url:}", configuration = EntitlementFeignConfiguration.class)
public interface ConsultationResourceProposalHttpClient {

	@GetMapping("/internal/v1/resource-proposals/{proposalId}")
	ResourceProposal response(@PathVariable UUID proposalId,
			@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId);

	record ResourceProposal(UUID proposalId, long version, UUID appointmentId, UUID userAccountId,
			UUID specialistAccountId, UUID summaryId, long summaryVersion, UUID completionFactId,
			UUID resourceId, String resourceVersion, String reasonCode, String title, String details,
			String summarySchemaVersion, Instant proposedAt) {
	}
}
