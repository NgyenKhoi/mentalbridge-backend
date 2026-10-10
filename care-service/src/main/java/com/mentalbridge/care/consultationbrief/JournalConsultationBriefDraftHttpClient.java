package com.mentalbridge.care.consultationbrief;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.mentalbridge.care.consultationbrief.ConsultationBriefAiDraftClient.DraftProviderRequest;
import com.mentalbridge.care.consultationbrief.ConsultationBriefAiDraftClient.DraftProviderResponse;

@FeignClient(name = "journal-ai-service", contextId = "consultationBriefAiDraftClient",
		url = "${mentalbridge.care.consultation-brief-ai-draft.base-url:}",
		configuration = ConsultationBriefAiDraftFeignConfiguration.class)
public interface JournalConsultationBriefDraftHttpClient {

	@PostMapping("/internal/v1/consultation-brief-drafts")
	DraftProviderResponse draft(@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId, @RequestBody DraftProviderRequest request);
}
