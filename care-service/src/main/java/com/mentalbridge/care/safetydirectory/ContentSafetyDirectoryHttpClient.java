package com.mentalbridge.care.safetydirectory;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.ProviderRequest;
import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.ProviderResponse;

@FeignClient(name = "content-notification-service", contextId = "safetyDirectoryClient",
		url = "${mentalbridge.care.safety-directory.base-url:}", configuration = SafetyDirectoryFeignConfiguration.class)
public interface ContentSafetyDirectoryHttpClient {
	@PostMapping("/api/v1/safety-directory:lookup")
	ProviderResponse lookup(@RequestHeader("X-Correlation-Id") String correlationId,
			@RequestBody ProviderRequest request);
}
