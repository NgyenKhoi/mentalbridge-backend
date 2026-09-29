package com.mentalbridge.care.consultationbrief;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import com.mentalbridge.care.entitlement.EntitlementFeignConfiguration;

@FeignClient(name = "consultation-service", contextId = "careAppointmentContextClient",
		url = "${mentalbridge.care.entitlement.base-url:}", configuration = EntitlementFeignConfiguration.class)
public interface ConsultationAppointmentHttpClient {

	@GetMapping("/internal/v1/appointments/{appointmentId}/consultation-brief-context")
	AppointmentContext context(@PathVariable UUID appointmentId,
			@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId);
}
