package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@FeignClient(name = "content-notification-service", contextId = "resourceEvidenceClient",
		url = "${mentalbridge.care.resource-evidence.base-url:}",
		configuration = ResourceEvidenceFeignConfiguration.class)
public interface ResourceEvidenceHttpClient {
	@GetMapping("/api/v1/resource-completion-evidence")
	Evidence read(@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId,
			@RequestParam UUID resourceId, @RequestParam String resourceVersion,
			@RequestParam LocalDate localDate, @RequestParam String timeZone,
			@RequestParam(required = false) List<String> excludedFactIds);

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Evidence(String factKind, String factId, String resourceVersion, Instant occurredAt, Instant verifiedAt) { }
}
