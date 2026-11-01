package com.mentalbridge.care.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.notification.UserNotificationOutboxRelayPersistence.PendingFact;

class UserNotificationOutboxRelayTests {

	private static final String TOPIC = "mentalbridge.care.user-notification.v1";
	private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

	@Test
	void publishesTheMinimizedContractEnvelopeThenMarksTheFactPublished() throws Exception {
		var persistence = mock(UserNotificationOutboxRelayPersistence.class);
		@SuppressWarnings("unchecked")
		var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
		var event = event();
		when(persistence.claimBatch()).thenReturn(List.of(event));
		when(kafka.send(eq(TOPIC), eq(event.aggregateId().toString()), anyString()))
				.thenReturn(CompletableFuture.completedFuture(null));

		new UserNotificationOutboxRelay(persistence, properties(), kafka, json).publishDue();

		var value = ArgumentCaptor.forClass(String.class);
		verify(kafka).send(eq(TOPIC), eq(event.aggregateId().toString()), value.capture());
		var envelope = json.readTree(value.getValue());
		assertThat(envelope.get("messageId").asText()).isEqualTo(event.id().toString());
		assertThat(envelope.get("messageType").asText()).isEqualTo("care.user-notification.fact");
		assertThat(envelope.get("payload").fieldNames()).toIterable().containsExactlyInAnyOrder(
				"recipientId", "factType", "targetId", "targetVersion", "availableAt");
		assertThat(envelope.toString()).doesNotContain("score", "answer", "safetyStatus", "title");
		verify(persistence).markPublished(event.id());
	}

	@Test
	void failedBrokerAcknowledgementLeavesTheFactRetryable() {
		var persistence = mock(UserNotificationOutboxRelayPersistence.class);
		@SuppressWarnings("unchecked")
		var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
		var event = event();
		var failed = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
		failed.completeExceptionally(new IllegalStateException("broker unavailable"));
		when(persistence.claimBatch()).thenReturn(List.of(event));
		when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed);

		new UserNotificationOutboxRelay(persistence, properties(), kafka, json).publishDue();

		verify(persistence).claimBatch();
		verify(persistence).markFailed(event.id(), event.attemptCount());
		verifyNoMoreInteractions(persistence);
	}

	private PendingFact event() {
		var id = UUID.fromString("10000000-0000-4000-8000-000000000001");
		var targetId = UUID.fromString("10000000-0000-4000-8000-000000000002");
		var recipientId = UUID.fromString("10000000-0000-4000-8000-000000000003");
		var occurredAt = Instant.parse("2026-10-19T08:00:00Z");
		var payload = json.createObjectNode()
				.put("recipientId", recipientId.toString())
				.put("factType", "ASSESSMENT_RESULT_AVAILABLE")
				.put("targetId", targetId.toString())
				.put("targetVersion", 0)
				.put("availableAt", occurredAt.toString());
		return new PendingFact(id, "care.user-notification.fact", "1.0", targetId, 0,
				UUID.fromString("10000000-0000-4000-8000-000000000004"), payload, occurredAt, 2);
	}

	private static UserNotificationRelayProperties properties() {
		return new UserNotificationRelayProperties(true, TOPIC, 100, Duration.ofSeconds(5),
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(5));
	}
}
