package com.mentalbridge.care.analytics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@FeignClient(name = "consultation-service", contextId = "careActivityConsultationClient",
		url = "${mentalbridge.care.activity-dashboard.consultation-base-url:}",
		configuration = ActivityDashboardFeignConfiguration.class)
public interface ConsultationActivityHttpClient {

	@GetMapping("/api/v1/appointments")
	AppointmentPage appointments(@RequestHeader("Authorization") String authorization,
			@RequestHeader("X-Correlation-Id") String correlationId);

	@JsonIgnoreProperties(ignoreUnknown = true)
	record AppointmentPage(List<Appointment> items, int count) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Appointment(List<HistoryEntry> history) { }

	@JsonIgnoreProperties(ignoreUnknown = true)
	record HistoryEntry(UUID eventId, String reason, Instant occurredAt) { }
}
