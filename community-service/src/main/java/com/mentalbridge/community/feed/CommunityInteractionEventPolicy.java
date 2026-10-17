package com.mentalbridge.community.feed;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.mentalbridge.community.notification.CommunityInteractionEventPublisher;
import com.mentalbridge.community.notification.CommunityInteractionFact;
import com.mentalbridge.community.notification.CommunityInteractionFact.InteractionKind;
import com.mentalbridge.community.notification.CommunityInteractionFact.TargetType;

@Component
class CommunityInteractionEventPolicy {

	private final CommunityInteractionEventPublisher publisher;

	CommunityInteractionEventPolicy(CommunityInteractionEventPublisher publisher) {
		this.publisher = publisher;
	}

	void commentCreated(CommunityCommentEntity comment, Instant occurredAt) {
		var targetAuthor = comment.parent() == null ? comment.post().author() : comment.parent().author();
		if (targetAuthor.id().equals(comment.author().id())) {
			return;
		}
		var targetType = comment.parent() == null ? TargetType.POST : TargetType.COMMENT;
		var targetId = comment.parent() == null ? comment.post().id() : comment.parent().id();
		var interactionKind = comment.parent() == null ? InteractionKind.COMMENT : InteractionKind.REPLY;
		publisher.publish("COMMENT:" + comment.id(), CommunityInteractionFact.create(comment.author().id(),
				targetAuthor.accountSubject(), targetType, targetId, interactionKind, comment.post().id(), occurredAt));
	}

	void reactionCreated(CommunityPostEntity post, CommunityProfileEntity actor,
			CommunityPostReactionEntity.Reaction reaction, Instant occurredAt) {
		if (post.author().id().equals(actor.id())) {
			return;
		}
		publisher.publish("REACTION:" + post.id() + ":" + actor.id(), CommunityInteractionFact.create(actor.id(),
				post.author().accountSubject(), TargetType.POST, post.id(), InteractionKind.valueOf(reaction.name()),
				post.id(), occurredAt));
	}
}
