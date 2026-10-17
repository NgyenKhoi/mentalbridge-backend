package com.mentalbridge.care.analytics;

import org.springframework.context.annotation.Bean;

import com.mentalbridge.care.configuration.ActivityDashboardClientProperties;

import feign.Request;
import feign.Retryer;

public class ActivityDashboardFeignConfiguration {

	@Bean
	Request.Options activityDashboardRequestOptions(ActivityDashboardClientProperties properties) {
		return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
	}

	@Bean
	Retryer activityDashboardFeignRetryer() {
		return Retryer.NEVER_RETRY;
	}
}
