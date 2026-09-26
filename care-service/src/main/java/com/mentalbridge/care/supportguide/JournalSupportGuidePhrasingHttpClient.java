package com.mentalbridge.care.supportguide;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.mentalbridge.care.supportguide.SupportGuidePhrasingClient.PhrasingRequest;
import com.mentalbridge.care.supportguide.SupportGuidePhrasingClient.PhrasingResponse;

@FeignClient(
		name = "journal-ai-service",
		contextId = "supportGuidePhrasingClient",
		url = "${mentalbridge.care.support-guide-phrasing.base-url:}",
		configuration = SupportGuidePhrasingFeignConfiguration.class)
public interface JournalSupportGuidePhrasingHttpClient {

	@PostMapping("/internal/v1/support-guide-phrasing")
	PhrasingResponse phrase(
			@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId,
			@RequestBody PhrasingRequest request);
}
