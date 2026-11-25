package com.mentalbridge.care.supportplan;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.CompanionRequest;
import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.CompanionResponse;

@FeignClient(
		name = "journal-ai-service",
		contextId = "supportPlanCompanionClient",
		url = "${mentalbridge.care.support-plan-companion.base-url:}",
		configuration = SupportPlanCompanionFeignConfiguration.class)
public interface JournalSupportPlanCompanionHttpClient {

	@PostMapping("/internal/v1/support-plan-companion/messages")
	CompanionResponse message(
			@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId,
			@RequestHeader("X-MentalBridge-Service-Token") String serviceToken,
			@RequestBody CompanionRequest request);
}
