package com.mentalbridge.consultation.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class AppointmentOutboxRelayTests {

	@Test
	void serializesNewAppointmentFactsAsVersionTwoWithoutSensitiveContent() throws Exception {
		var mapper = new ObjectMapper().findAndRegisterModules();
		var relay = new AppointmentOutboxRelay(null, null, null, mapper, Clock.systemUTC());
		var appointmentId = UUID.fromString("25000000-0000-4000-8000-000000000003");
		var specialistId = UUID.fromString("25000000-0000-4000-8000-000000000005");
		var payload = """
				{
				  "appointmentId":"%s",
				  "ownerAccountId":"25000000-0000-4000-8000-000000000004",
				  "specialistAccountId":"%s",
				  "status":"CONFIRMED",
				  "scheduledStartAt":"2030-01-02T03:00:00Z",
				  "modality":"IN_APP_CHAT",
				  "replacesAppointmentId":null
				}
				""".formatted(appointmentId, specialistId);
		var event = new AppointmentOutboxRelay.PendingEvent(
				UUID.fromString("25000000-0000-4000-8000-000000000001"), appointmentId, 3,
				UUID.fromString("25000000-0000-4000-8000-000000000002"), payload,
				Instant.parse("2029-01-01T00:00:00Z"), 1);

		var envelope = mapper.readTree(relay.serialize(event));

		assertThat(envelope.path("schemaVersion").asText()).isEqualTo("2.0");
		assertThat(envelope.path("aggregateId").asText()).isEqualTo(appointmentId.toString());
		assertThat(envelope.at("/payload/specialistAccountId").asText()).isEqualTo(specialistId.toString());
		assertThat(envelope.toString()).doesNotContainIgnoringCase("journal", "assessmentAnswers",
				"chatBody", "consultationBrief", "sessionSummary");
	}
}
