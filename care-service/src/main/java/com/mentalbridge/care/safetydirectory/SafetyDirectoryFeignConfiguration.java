package com.mentalbridge.care.safetydirectory;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.SafetyDirectoryClientProperties;

import feign.Request;
import feign.Retryer;

public class SafetyDirectoryFeignConfiguration {
	@Bean
	Request.Options safetyDirectoryRequestOptions(SafetyDirectoryClientProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer safetyDirectoryRetryer() { return Retryer.NEVER_RETRY; }
}
