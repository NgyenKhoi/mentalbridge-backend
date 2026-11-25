package com.mentalbridge.community.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mentalbridge.community.notification.CommunityInteractionOutboxRelayPersistence.PendingInteractionEvent;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.community.interaction-relay", name = "enabled",
		havingValue = "true")
class CommunityInteractionOutboxRelay {

	private static final Logger LOGGER = LoggerFactory.getLogger(CommunityInteractionOutboxRelay.class);

	private final CommunityInteractionOutboxRelayPersistence persistence;
	private final CommunityInteractionEventSink sink;

	CommunityInteractionOutboxRelay(CommunityInteractionOutboxRelayPersistence persistence,
			CommunityInteractionEventSink sink) {
		this.persistence = persistence;
		this.sink = sink;
	}

	@Scheduled(fixedDelayString = "${mentalbridge.community.interaction-relay.interval}")
	void publishDue() {
		for (var event : persistence.claimBatch()) {
			publish(event);
		}
	}

	private void publish(PendingInteractionEvent event) {
		try {
			sink.publish(event.targetId(), event.eventPayload());
			persistence.markPublished(event.id());
		}
		catch (Exception exception) {
			persistence.markFailed(event.id(), event.attemptCount());
			LOGGER.warn("Community interaction relay failed eventId={} attempt={}", event.id(), event.attemptCount());
		}
	}
}
