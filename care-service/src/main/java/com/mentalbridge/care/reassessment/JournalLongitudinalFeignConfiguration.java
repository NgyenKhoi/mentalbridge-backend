package com.mentalbridge.care.reassessment;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.JournalLongitudinalClientProperties;

import feign.Request;
import feign.Retryer;

public class JournalLongitudinalFeignConfiguration {

	@Bean
	Request.Options journalLongitudinalRequestOptions(JournalLongitudinalClientProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer journalLongitudinalFeignRetryer() {
		return Retryer.NEVER_RETRY;
	}
}
