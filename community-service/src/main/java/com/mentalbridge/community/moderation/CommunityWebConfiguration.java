package com.mentalbridge.community.moderation;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class CommunityWebConfiguration implements WebMvcConfigurer {

	private final CommunityAccessInterceptor access;

	CommunityWebConfiguration(CommunityAccessInterceptor access) {
		this.access = access;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(access).addPathPatterns("/api/v1/community/**");
	}
}
