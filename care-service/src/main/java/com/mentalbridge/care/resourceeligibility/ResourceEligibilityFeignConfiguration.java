package com.mentalbridge.care.resourceeligibility;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.ResourceEligibilityClientProperties;

import feign.Request;
import feign.Retryer;

public class ResourceEligibilityFeignConfiguration {

	@Bean
	Request.Options resourceEligibilityRequestOptions(ResourceEligibilityClientProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer resourceEligibilityFeignRetryer() {
		return Retryer.NEVER_RETRY;
	}
}
