package com.mentalbridge.care.consultationbrief;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.ConsultationBriefAiDraftClientProperties;

import feign.Request;
import feign.Retryer;

public class ConsultationBriefAiDraftFeignConfiguration {

	@Bean
	Request.Options consultationBriefAiDraftRequestOptions(ConsultationBriefAiDraftClientProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer consultationBriefAiDraftFeignRetryer() {
		return Retryer.NEVER_RETRY;
	}
}
