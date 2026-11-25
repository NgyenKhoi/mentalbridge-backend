package com.mentalbridge.care.supportplan;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.EmotionCheckInEvidenceProperties;

import feign.Request;
import feign.Retryer;

public class EmotionCheckInEvidenceFeignConfiguration {
	@Bean
	Request.Options emotionCheckInEvidenceRequestOptions(EmotionCheckInEvidenceProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer emotionCheckInEvidenceRetryer() {
		return Retryer.NEVER_RETRY;
	}
}
