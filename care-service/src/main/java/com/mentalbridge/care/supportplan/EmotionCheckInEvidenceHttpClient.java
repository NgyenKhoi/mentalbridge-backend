package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@FeignClient(name = "journal-ai-service", contextId = "emotionCheckInEvidenceClient",
		url = "${mentalbridge.care.emotion-check-in-evidence.base-url:}",
		configuration = EmotionCheckInEvidenceFeignConfiguration.class)
public interface EmotionCheckInEvidenceHttpClient {

	@GetMapping("/api/v1/emotion-check-ins/{localDate}/completion-evidence")
	Evidence read(@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId,
			@PathVariable LocalDate localDate);

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Evidence(String schemaVersion, UUID subjectUserId, LocalDate localDate,
			String state, Fact fact, Instant verifiedAt) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Fact(String ownerDomain, String kind, UUID factId, String factRevision,
			Instant occurredAt, String timezone) { }
}
