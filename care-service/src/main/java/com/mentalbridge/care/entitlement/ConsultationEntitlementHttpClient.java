package com.mentalbridge.care.entitlement;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
		name = "consultation-service",
		contextId = "careEntitlementClient",
		url = "${mentalbridge.care.entitlement.base-url:}",
		configuration = EntitlementFeignConfiguration.class)
public interface ConsultationEntitlementHttpClient {

	@GetMapping("/internal/v1/entitlements/current")
	CurrentEntitlementResponse current(
			@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId);
}
