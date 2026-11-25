package com.mentalbridge.consultation.appointment;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AppointmentExpiryScheduler {

	private static final Logger LOGGER = LoggerFactory.getLogger(AppointmentExpiryScheduler.class);

	private final AppointmentDecisionService decisions;

	public AppointmentExpiryScheduler(AppointmentDecisionService decisions) {
		this.decisions = decisions;
	}

	@Scheduled(fixedDelayString = "${mentalbridge.consultation.appointments.expiry-interval:PT1M}")
	public void expireDueRequests() {
		var after = new UUID(0, 0);
		for (var page = decisions.dueRequestIds(after); !page.isEmpty(); page = decisions.dueRequestIds(after)) {
			for (var appointmentId : page) {
				try {
					decisions.expire(appointmentId);
				}
				catch (RuntimeException exception) {
					LOGGER.error("Appointment request expiry failed for appointmentId={}", appointmentId, exception);
				}
			}
			after = page.getLast();
		}
	}
}
