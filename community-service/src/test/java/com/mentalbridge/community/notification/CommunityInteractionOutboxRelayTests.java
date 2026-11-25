package com.mentalbridge.community.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.community.notification.CommunityInteractionOutboxRelayPersistence.PendingInteractionEvent;
import org.junit.jupiter.api.Test;

class CommunityInteractionOutboxRelayTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void publishesThroughAnInMemoryAdapterAndMarksTheClaimedEventComplete() {
		var persistence = mock(CommunityInteractionOutboxRelayPersistence.class);
		var event = pendingEvent();
		var sink = new RecordingSink(false);
		when(persistence.claimBatch()).thenReturn(List.of(event));

		new CommunityInteractionOutboxRelay(persistence, sink).publishDue();

		assertThat(sink.payloads).containsExactly(event.eventPayload());
		verify(persistence).claimBatch();
		verify(persistence).markPublished(event.id());
		verifyNoMoreInteractions(persistence);
	}

	@Test
	void unavailableBrokerSchedulesAnIndependentRetryWithoutEscapingTheRelay() {
		var persistence = mock(CommunityInteractionOutboxRelayPersistence.class);
		var event = pendingEvent();
		when(persistence.claimBatch()).thenReturn(List.of(event));

		new CommunityInteractionOutboxRelay(persistence, new RecordingSink(true)).publishDue();

		verify(persistence).claimBatch();
		verify(persistence).markFailed(event.id(), event.attemptCount());
		verifyNoMoreInteractions(persistence);
	}

	private PendingInteractionEvent pendingEvent() {
		var id = UUID.fromString("00000000-0000-0000-0000-000000000121");
		var targetId = UUID.fromString("00000000-0000-0000-0000-000000000122");
		JsonNode payload = objectMapper.createObjectNode().put("eventId", id.toString());
		return new PendingInteractionEvent(id, targetId, payload, Instant.parse("2026-10-03T10:15:30Z"), 2);
	}

	private static final class RecordingSink implements CommunityInteractionEventSink {

		private final List<JsonNode> payloads = new ArrayList<>();
		private final boolean fail;

		private RecordingSink(boolean fail) {
			this.fail = fail;
		}

		@Override
		public void publish(UUID targetId, JsonNode eventPayload) {
			if (fail) {
				throw new IllegalStateException("broker unavailable");
			}
			payloads.add(eventPayload);
		}
	}
}
