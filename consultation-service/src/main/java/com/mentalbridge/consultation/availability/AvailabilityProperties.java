package com.mentalbridge.consultation.availability;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mentalbridge.consultation.availability")
public record AvailabilityProperties(boolean videoEnabled) {
}
