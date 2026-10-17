package com.mentalbridge.care.resourceeligibility;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchRequest;
import com.mentalbridge.care.resourceeligibility.generated.ResourceEligibilityContract.ResourceEligibilityBatchResponse;

@FeignClient(
		name = "content-notification-service",
		contextId = "resourceEligibilityClient",
		url = "${mentalbridge.care.resource-eligibility.base-url:}",
		configuration = ResourceEligibilityFeignConfiguration.class)
public interface ContentResourceEligibilityHttpClient {

	@PostMapping("/internal/v1/resource-eligibility:resolve")
	ResourceEligibilityBatchResponse resolve(
			@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId,
			@RequestBody ResourceEligibilityBatchRequest request);
}
