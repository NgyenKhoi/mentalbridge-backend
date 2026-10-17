package com.mentalbridge.community.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class CommunityInteractionOutboxPublisher implements CommunityInteractionEventPublisher {

	private final CommunityInteractionOutboxEventRepository events;
	private final ObjectMapper objectMapper;

	CommunityInteractionOutboxPublisher(CommunityInteractionOutboxEventRepository events,
			ObjectMapper objectMapper) {
		this.events = events;
		this.objectMapper = objectMapper;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void publish(String deduplicationKey, CommunityInteractionFact fact) {
		if (!events.existsByDeduplicationKey(deduplicationKey)) {
			events.saveAndFlush(new CommunityInteractionOutboxEventEntity(deduplicationKey, fact,
					objectMapper.valueToTree(fact)));
		}
	}
}
