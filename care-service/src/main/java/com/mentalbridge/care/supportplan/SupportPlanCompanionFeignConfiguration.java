package com.mentalbridge.care.supportplan;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.SupportPlanCompanionClientProperties;

import feign.Request;
import feign.Retryer;

public class SupportPlanCompanionFeignConfiguration {

	@Bean
	Request.Options supportPlanCompanionRequestOptions(SupportPlanCompanionClientProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer supportPlanCompanionFeignRetryer() {
		return Retryer.NEVER_RETRY;
	}
}
