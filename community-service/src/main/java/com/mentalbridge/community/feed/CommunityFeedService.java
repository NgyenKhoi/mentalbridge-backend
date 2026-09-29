package com.mentalbridge.community.feed;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.community.feed.CommunityResponses.Author;
import com.mentalbridge.community.feed.CommunityResponses.AuthorState;
import com.mentalbridge.community.feed.CommunityResponses.Counts;
import com.mentalbridge.community.feed.CommunityResponses.Feed;
import com.mentalbridge.community.feed.CommunityResponses.Media;
import com.mentalbridge.community.feed.CommunityResponses.MediaAvailability;
import com.mentalbridge.community.feed.CommunityResponses.MediaType;
import com.mentalbridge.community.feed.CommunityResponses.PostDetail;
import com.mentalbridge.community.feed.CommunityResponses.PostSummary;
import com.mentalbridge.community.feed.CommunityResponses.Topic;
import com.mentalbridge.community.shared.CommunityApiException;

@Service
public class CommunityFeedService {

	private static final int PREVIEW_CODE_POINTS = 420;
	private static final String DELETED_AUTHOR_NAME = "Thành viên đã rời cộng đồng";

	private final CommunityPostRepository posts;
	private final CommunityProfileRepository profiles;

	public CommunityFeedService(CommunityPostRepository posts, CommunityProfileRepository profiles) {
		this.posts = posts;
		this.profiles = profiles;
	}

	@Transactional(readOnly = true)
	public Feed feed(UUID accountSubject, CommunityTopic topic, String cursorValue, int limit) {
		var cursor = decode(cursorValue);
		var viewerProfileId = profiles.findIdByAccountSubject(accountSubject).orElse(null);
		var page = posts.findFeed(topic == null ? null : topic.name(), viewerProfileId,
				cursor == null ? null : cursor.publishedAt(), cursor == null ? null : cursor.postId(),
				PageRequest.of(0, limit + 1));
		var hasMore = page.size() > limit;
		var visible = hasMore ? page.subList(0, limit) : page;
		var items = visible.stream().map(this::summary).toList();
		var nextCursor = hasMore ? encode(visible.getLast().publishedAt(), visible.getLast().id()) : null;
		return new Feed(items, nextCursor, hasMore);
	}

	@Transactional(readOnly = true)
	public VersionedPost detail(UUID accountSubject, UUID postId) {
		var viewerProfileId = profiles.findIdByAccountSubject(accountSubject).orElse(null);
		return posts.findVisibleById(postId, viewerProfileId)
				.map(post -> new VersionedPost(toDetail(post), post.author().accountSubject().equals(accountSubject)
						? post.version() : null))
				.orElseThrow(CommunityApiException::postNotFound);
	}

	public List<Topic> topics() {
		return Arrays.stream(CommunityTopic.values())
				.map(topic -> new Topic(topic, topic.label(), topic.description()))
				.toList();
	}

	private PostSummary summary(CommunityPostEntity post) {
		return new PostSummary(post.id(), author(post.author()), preview(post.content()), sortedTopics(post), media(post),
				mediaAvailability(post), counts(post), post.publishedAt(), post.updatedAt());
	}

	PostDetail toDetail(CommunityPostEntity post) {
		return new PostDetail(post.id(), author(post.author()), post.content(), sortedTopics(post), media(post),
				mediaAvailability(post), counts(post), post.publishedAt(), post.updatedAt());
	}

	private Author author(CommunityProfileEntity profile) {
		var deleted = profile.status() == CommunityProfileEntity.Status.DELETED;
		return new Author(profile.id(), deleted ? DELETED_AUTHOR_NAME : profile.displayName(),
				deleted ? AuthorState.DELETED : AuthorState.ACTIVE);
	}

	private List<CommunityTopic> sortedTopics(CommunityPostEntity post) {
		return post.topics().stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
	}

	private List<Media> media(CommunityPostEntity post) {
		return post.media().stream()
				.filter(item -> item.state() == CommunityMediaEntity.State.READY)
				.map(item -> new Media(item.id(), MediaType.valueOf(item.mediaType().name()), item.deliveryUrl(),
						item.width(), item.height(), item.durationSeconds(), item.altText()))
				.toList();
	}

	private MediaAvailability mediaAvailability(CommunityPostEntity post) {
		if (post.media().isEmpty()) {
			return MediaAvailability.NONE;
		}
		var ready = post.media().stream().filter(item -> item.state() == CommunityMediaEntity.State.READY).count();
		if (ready == post.media().size()) {
			return MediaAvailability.READY;
		}
		return ready == 0 ? MediaAvailability.UNAVAILABLE : MediaAvailability.PARTIAL;
	}

	private Counts counts(CommunityPostEntity post) {
		return new Counts(post.commentCount(), post.reactionCount());
	}

	private String preview(String content) {
		var points = content.codePoints().toArray();
		if (points.length <= PREVIEW_CODE_POINTS) {
			return content;
		}
		return new String(points, 0, PREVIEW_CODE_POINTS) + "…";
	}

	private String encode(Instant publishedAt, UUID postId) {
		var value = publishedAt + "|" + postId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private Cursor decode(String value) {
		if (value == null) {
			return null;
		}
		try {
			var decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			var separator = decoded.lastIndexOf('|');
			if (separator <= 0 || separator == decoded.length() - 1) {
				throw CommunityApiException.invalidCursor();
			}
			return new Cursor(Instant.parse(decoded.substring(0, separator)),
					UUID.fromString(decoded.substring(separator + 1)));
		}
		catch (IllegalArgumentException exception) {
			throw CommunityApiException.invalidCursor();
		}
	}

	private record Cursor(Instant publishedAt, UUID postId) {
	}

	public record VersionedPost(PostDetail body, Long ownerVersion) {
	}
}
