package com.mentalbridge.consultation.appointment;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;

public record ChatEvidenceRequest(@NotNull UUID evidenceId, @NotNull Type type,
		@NotNull Instant occurredAt, Instant intervalStartedAt, UUID messageId) {

	@JsonAnySetter
	public void rejectUnknownProperty(String name, Object value) {
		throw new IllegalArgumentException("Unsupported chat evidence field: " + name);
	}

	public enum Type {
		CHECK_IN, PRESENCE_INTERVAL, ACCEPTED_MESSAGE
	}
}
