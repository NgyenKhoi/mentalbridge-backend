package com.mentalbridge.community.notification;

public interface CommunityInteractionEventPublisher {

	void publish(String deduplicationKey, CommunityInteractionFact fact);
}
