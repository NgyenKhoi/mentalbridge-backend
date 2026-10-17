package com.mentalbridge.care.reassessment;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Evidence;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Job;

@FeignClient(
		name = "journal-ai-service",
		contextId = "journalLongitudinalClient",
		url = "${mentalbridge.care.journal-longitudinal.base-url:}",
		configuration = JournalLongitudinalFeignConfiguration.class)
public interface JournalLongitudinalHttpClient {

	@GetMapping("/api/v1/longitudinal-analysis-jobs/{jobId}")
	Job getJob(
			@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId,
			@PathVariable UUID jobId);

	@GetMapping("/internal/v1/users/{userId}/longitudinal-analyses/{analysisId}")
	Evidence get(
			@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId,
			@PathVariable UUID userId,
			@PathVariable UUID analysisId,
			@RequestParam("purpose") String purpose);
}
