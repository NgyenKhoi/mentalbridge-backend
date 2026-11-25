package com.mentalbridge.care.entitlement;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.EntitlementClientProperties;

import feign.Request;
import feign.Retryer;

public class EntitlementFeignConfiguration {

	@Bean
	Request.Options entitlementRequestOptions(EntitlementClientProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer entitlementFeignRetryer() {
		return Retryer.NEVER_RETRY;
	}
}
