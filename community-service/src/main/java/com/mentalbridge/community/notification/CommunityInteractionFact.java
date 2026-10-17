package com.mentalbridge.community.notification;

import java.time.Instant;
import java.util.UUID;

public record CommunityInteractionFact(UUID eventId, String schemaVersion, UUID actorCommunityProfileId,
		UUID targetOwnerRoutingReference, TargetType targetType, UUID targetId, InteractionKind interactionKind,
		Instant occurredAt, DeepLink deepLink) {

	public static CommunityInteractionFact create(UUID actorCommunityProfileId, UUID targetOwnerRoutingReference,
			TargetType targetType, UUID targetId, InteractionKind interactionKind, UUID postId, Instant occurredAt) {
		return new CommunityInteractionFact(UUID.randomUUID(), "1.0", actorCommunityProfileId,
				targetOwnerRoutingReference, targetType, targetId, interactionKind, occurredAt,
				new DeepLink(DeepLinkKind.COMMUNITY_POST, postId));
	}

	public enum TargetType { POST, COMMENT }

	public enum InteractionKind { COMMENT, REPLY, SUPPORT, RELATE, THANK_YOU }

	public enum DeepLinkKind { COMMUNITY_POST }

	public record DeepLink(DeepLinkKind kind, UUID postId) {
	}
}
