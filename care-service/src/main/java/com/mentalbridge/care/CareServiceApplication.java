package com.mentalbridge.care;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

import com.mentalbridge.care.resourceeligibility.ContentResourceEligibilityHttpClient;
import com.mentalbridge.care.safetydirectory.ContentSafetyDirectoryHttpClient;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients(clients = { ContentResourceEligibilityHttpClient.class, ContentSafetyDirectoryHttpClient.class })
public class CareServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CareServiceApplication.class, args);
	}

}
