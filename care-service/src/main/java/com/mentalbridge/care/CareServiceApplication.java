package com.mentalbridge.care;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

import com.mentalbridge.care.entitlement.ConsultationEntitlementHttpClient;
import com.mentalbridge.care.consultationbrief.ConsultationAppointmentHttpClient;
import com.mentalbridge.care.reassessment.JournalLongitudinalHttpClient;
import com.mentalbridge.care.resourceeligibility.ContentResourceEligibilityHttpClient;
import com.mentalbridge.care.safetydirectory.ContentSafetyDirectoryHttpClient;
import com.mentalbridge.care.supportguide.JournalSupportGuidePhrasingHttpClient;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients(clients = { ContentResourceEligibilityHttpClient.class, ContentSafetyDirectoryHttpClient.class,
		ConsultationEntitlementHttpClient.class, JournalLongitudinalHttpClient.class,
		JournalSupportGuidePhrasingHttpClient.class, ConsultationAppointmentHttpClient.class })
public class CareServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CareServiceApplication.class, args);
	}

}
