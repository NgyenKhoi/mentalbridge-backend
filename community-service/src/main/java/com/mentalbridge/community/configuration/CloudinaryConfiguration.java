package com.mentalbridge.community.configuration;

import java.util.Map;

import com.cloudinary.Cloudinary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CloudinaryConfiguration {

	@Bean
	Cloudinary cloudinary(CloudinaryProperties properties) {
		return new Cloudinary(Map.of(
				"cloud_name", properties.cloudName(),
				"api_key", properties.apiKey(),
				"api_secret", properties.apiSecret(),
				"timeout", properties.timeoutSeconds(),
				"secure", true));
	}

}
