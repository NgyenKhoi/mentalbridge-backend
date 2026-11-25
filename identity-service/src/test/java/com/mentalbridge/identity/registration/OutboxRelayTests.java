package com.mentalbridge.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.configuration.OutboxRelayProperties;
import com.mentalbridge.identity.registration.OutboxRelayPersistence.PendingOutboxEvent;

class OutboxRelayTests {

	private static final String TOPIC = "mentalbridge.identity.account-lifecycle.v1";

	@Test
	void publishesTheContractEnvelopeAndMarksTheOutboxEventPublished() throws Exception {
		var persistence = mock(OutboxRelayPersistence.class);
		@SuppressWarnings("unchecked")
		var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
		var event = event();
		when(persistence.claimBatch()).thenReturn(List.of(event));
		when(kafka.send(org.mockito.ArgumentMatchers.eq(TOPIC),
				org.mockito.ArgumentMatchers.eq(event.aggregateId().toString()),
				org.mockito.ArgumentMatchers.anyString())).thenReturn(CompletableFuture.completedFuture(null));

		new OutboxRelay(persistence, properties(), kafka, new ObjectMapper().findAndRegisterModules()).publishDue();

		var payload = ArgumentCaptor.forClass(String.class);
		verify(kafka).send(org.mockito.ArgumentMatchers.eq(TOPIC),
				org.mockito.ArgumentMatchers.eq(event.aggregateId().toString()), payload.capture());
		var envelope = new ObjectMapper().findAndRegisterModules().readTree(payload.getValue());
		assertThat(envelope.get("messageId").asText()).isEqualTo(event.id().toString());
		assertThat(envelope.get("messageType").asText()).isEqualTo("identity.account.registered");
		assertThat(envelope.get("payload").get("actorType").asText()).isEqualTo("USER");
		verify(persistence).markPublished(event.id());
	}

	@Test
	void leavesTheEventRetryableWhenKafkaDoesNotAcknowledgeIt() {
		var persistence = mock(OutboxRelayPersistence.class);
		@SuppressWarnings("unchecked")
		var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
		var event = event();
		var failed = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
		failed.completeExceptionally(new IllegalStateException("broker unavailable"));
		when(persistence.claimBatch()).thenReturn(List.of(event));
		when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString())).thenReturn(failed);

		new OutboxRelay(persistence, properties(), kafka, new ObjectMapper().findAndRegisterModules()).publishDue();

		verify(persistence).claimBatch();
		verify(persistence).markFailed(event.id(), event.attemptCount());
		verifyNoMoreInteractions(persistence);
	}

	private static PendingOutboxEvent event() {
		var accountId = UUID.fromString("33333333-3333-4333-8333-333333333333");
		return new PendingOutboxEvent(UUID.fromString("11111111-1111-4111-8111-111111111111"),
				"identity.account.registered", "1.0", accountId, 0,
				UUID.fromString("22222222-2222-4222-8222-222222222222"),
				Map.of("accountId", accountId.toString(), "actorType", "USER", "status",
						"PENDING_EMAIL_VERIFICATION"),
				Instant.parse("2026-09-28T08:00:00Z"), 1);
	}

	private static OutboxRelayProperties properties() {
		return new OutboxRelayProperties(true, TOPIC, 100, Duration.ofSeconds(5), Duration.ofSeconds(5),
				Duration.ofSeconds(5), Duration.ofMinutes(5));
	}
}
