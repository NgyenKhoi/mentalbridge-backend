package com.mentalbridge.care.supportplan;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.ResourceEvidenceProperties;

import feign.Request;
import feign.Retryer;

public class ResourceEvidenceFeignConfiguration {
	@Bean
	Request.Options resourceEvidenceRequestOptions(ResourceEvidenceProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer resourceEvidenceRetryer() {
		return Retryer.NEVER_RETRY;
	}
}
