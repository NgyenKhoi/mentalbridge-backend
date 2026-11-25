package com.mentalbridge.consultation.discovery;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "care-service", contextId = "careSupportEvaluationClient")
interface CareSupportEvaluationClient {

	@GetMapping("/api/v2/support-evaluations/{supportEvaluationId}")
	SupportEvaluation get(@PathVariable UUID supportEvaluationId,
			@RequestHeader("Authorization") String authorization);

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SupportEvaluation(UUID supportEvaluationId, String policyVersion,
			List<DomainContribution> contributingDomains) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	record DomainContribution(String domain, String screeningLevel, String supportPathway) { }
}
