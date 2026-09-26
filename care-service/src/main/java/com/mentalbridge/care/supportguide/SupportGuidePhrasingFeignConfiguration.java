package com.mentalbridge.care.supportguide;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.SupportGuidePhrasingClientProperties;

import feign.Request;
import feign.Retryer;

public class SupportGuidePhrasingFeignConfiguration {

	@Bean
	Request.Options supportGuidePhrasingRequestOptions(SupportGuidePhrasingClientProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer supportGuidePhrasingFeignRetryer() {
		return Retryer.NEVER_RETRY;
	}
}
