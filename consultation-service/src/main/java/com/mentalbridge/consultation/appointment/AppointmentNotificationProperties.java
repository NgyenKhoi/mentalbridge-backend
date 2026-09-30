package com.mentalbridge.consultation.appointment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mentalbridge.consultation.appointment-notifications")
public record AppointmentNotificationProperties(String serviceToken, boolean relayEnabled, String topic,
		int batchSize, Duration relayInterval, Duration sendTimeout, Duration retryBase, Duration retryMaximum) {
}
