package com.mentalbridge.care.analytics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@FeignClient(name = "journal-ai-service", contextId = "careActivityJournalClient",
		url = "${mentalbridge.care.activity-dashboard.journal-base-url:}",
		configuration = ActivityDashboardFeignConfiguration.class)
public interface JournalActivityHttpClient {

	@GetMapping("/api/v1/journals")
	JournalPage journals(@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId, @RequestParam int limit);

	@GetMapping("/api/v1/emotion-check-ins")
	EmotionPage emotions(@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId, @RequestParam int limit);

	@JsonIgnoreProperties(ignoreUnknown = true)
	record JournalPage(List<JournalItem> items, Page page) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	record JournalItem(UUID id, Instant occurredAt) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	record EmotionPage(List<EmotionItem> items, Page page) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	record EmotionItem(UUID id, String emotion, Instant recordedAt) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Page(int limit, boolean hasMore) { }
}
