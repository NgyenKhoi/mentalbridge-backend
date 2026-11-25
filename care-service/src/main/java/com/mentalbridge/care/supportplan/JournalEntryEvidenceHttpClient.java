package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@FeignClient(name = "journal-ai-service", contextId = "journalEntryEvidenceClient",
		url = "${mentalbridge.care.journal-entry-evidence.base-url:}",
		configuration = JournalEntryEvidenceFeignConfiguration.class)
public interface JournalEntryEvidenceHttpClient {

	@GetMapping("/api/v1/journals/{journalId}/completion-evidence")
	Evidence read(@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId,
			@PathVariable UUID journalId);

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Evidence(String schemaVersion, UUID subjectUserId, UUID journalId,
			String state, Fact fact, Instant verifiedAt) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Fact(String ownerDomain, String kind, UUID factId, String factRevision,
			Instant occurredAt) { }
}
