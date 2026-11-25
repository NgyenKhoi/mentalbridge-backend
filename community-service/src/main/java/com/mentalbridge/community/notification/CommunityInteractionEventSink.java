package com.mentalbridge.community.notification;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

interface CommunityInteractionEventSink {

	void publish(UUID targetId, JsonNode eventPayload) throws Exception;
}
