package com.mentalbridge.consultation.appointment;

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
		for (var appointmentId : decisions.dueRequestIds()) {
			try {
				decisions.expire(appointmentId);
			}
			catch (RuntimeException exception) {
				LOGGER.error("Appointment request expiry failed for appointmentId={}", appointmentId, exception);
			}
		}
	}
}
