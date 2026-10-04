package com.mentalbridge.community.feed;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.mentalbridge.community.feed.CommunityResponses.ResourceAttachment;
import com.mentalbridge.community.feed.CommunityResponses.Topic;
import com.mentalbridge.community.feed.CommunityResponses.ViewerState;
import com.mentalbridge.community.shared.CommunityApiException;

@Service
public class CommunityFeedService {

	private static final int PREVIEW_CODE_POINTS = 420;
	private static final String ANONYMOUS_AUTHOR_NAME = "Thành viên ẩn danh";
	private static final String DELETED_AUTHOR_NAME = "Thành viên đã rời cộng đồng";

	private final CommunityPostRepository posts;
	private final CommunityProfileRepository profiles;
	private final CommunityPostReactionRepository reactions;
	private final CommunityPostBookmarkRepository bookmarks;
	private final CommunityTopicService topicService;

	public CommunityFeedService(CommunityPostRepository posts, CommunityProfileRepository profiles,
			CommunityPostReactionRepository reactions, CommunityPostBookmarkRepository bookmarks,
			CommunityTopicService topicService) {
		this.posts = posts;
		this.profiles = profiles;
		this.reactions = reactions;
		this.bookmarks = bookmarks;
		this.topicService = topicService;
	}

	@Transactional(readOnly = true)
	public Feed feed(UUID accountSubject, List<CommunityTopic> requestedTopics, String cursorValue, int limit) {
		var cursor = decode(cursorValue);
		var selectedTopics = topicService.validateFilter(requestedTopics);
		var viewerProfileId = profiles.findIdByAccountSubject(accountSubject).orElse(null);
		var topicCodes = selectedTopics.isEmpty() ? List.of(CommunityTopic.MY_STORY.name())
				: selectedTopics.stream().map(Enum::name).toList();
		var page = posts.findFeed(!selectedTopics.isEmpty(), topicCodes, viewerProfileId,
				cursor == null ? null : cursor.publishedAt(), cursor == null ? null : cursor.postId(),
				PageRequest.of(0, limit + 1));
		var hasMore = page.size() > limit;
		var visible = hasMore ? page.subList(0, limit) : page;
		var states = viewerStates(visible, viewerProfileId);
		var items = visible.stream().map(post -> summary(post, states.get(post.id()))).toList();
		var nextCursor = hasMore ? encode(visible.getLast().publishedAt(), visible.getLast().id()) : null;
		return new Feed(items, nextCursor, hasMore);
	}

	@Transactional(readOnly = true)
	public VersionedPost detail(UUID accountSubject, UUID postId) {
		var viewerProfileId = profiles.findIdByAccountSubject(accountSubject).orElse(null);
		return posts.findVisibleById(postId, viewerProfileId)
				.map(post -> new VersionedPost(toDetail(post, viewerProfileId), post.author().accountSubject().equals(accountSubject)
						? post.version() : null))
				.orElseThrow(CommunityApiException::postNotFound);
	}

	public List<Topic> topics() {
		return topicService.activeTopics();
	}

	private PostSummary summary(CommunityPostEntity post, ViewerState viewerState) {
		return new PostSummary(post.id(), author(post), preview(post.content()), sortedTopics(post), media(post),
				mediaAvailability(post), resourceAttachment(post), post.sensitiveContentWarning(), counts(post),
				viewerState, post.publishedAt(), post.updatedAt());
	}

	PostDetail toDetail(CommunityPostEntity post, UUID viewerProfileId) {
		return new PostDetail(post.id(), author(post), post.content(), sortedTopics(post), media(post),
				mediaAvailability(post), resourceAttachment(post), post.sensitiveContentWarning(), counts(post),
				viewerState(post.id(), viewerProfileId), post.publishedAt(), post.updatedAt());
	}

	private ResourceAttachment resourceAttachment(CommunityPostEntity post) {
		return post.resourceId() == null ? null : new ResourceAttachment(post.resourceId());
	}

	private Map<UUID, ViewerState> viewerStates(List<CommunityPostEntity> visible, UUID viewerProfileId) {
		if (viewerProfileId == null || visible.isEmpty()) {
			return visible.stream().collect(Collectors.toMap(CommunityPostEntity::id,
					post -> new ViewerState(null, false)));
		}
		var postIds = visible.stream().map(CommunityPostEntity::id).toList();
		var byPost = reactions.findAllByProfileIdAndPostIdIn(viewerProfileId, postIds).stream()
				.collect(Collectors.toMap(CommunityPostReactionEntity::postId, Function.identity()));
		var bookmarked = bookmarks.findAllByProfileIdAndPostIdIn(viewerProfileId, postIds).stream()
				.map(CommunityPostBookmarkEntity::postId).collect(Collectors.toSet());
		return visible.stream().collect(Collectors.toMap(CommunityPostEntity::id, post -> {
			var reaction = byPost.get(post.id());
			return new ViewerState(reaction == null ? null : reaction.reaction(), bookmarked.contains(post.id()));
		}));
	}

	private ViewerState viewerState(UUID postId, UUID viewerProfileId) {
		if (viewerProfileId == null) {
			return new ViewerState(null, false);
		}
		var reaction = reactions.findById(new CommunityPostReactionEntity.CommunityPostReactionId(postId,
				viewerProfileId)).map(CommunityPostReactionEntity::reaction).orElse(null);
		var bookmarked = bookmarks.existsById(new CommunityPostBookmarkEntity.CommunityPostBookmarkId(postId,
				viewerProfileId));
		return new ViewerState(reaction, bookmarked);
	}

	private Author author(CommunityPostEntity post) {
		if (post.authorMode() == CommunityPostEntity.AuthorMode.ANONYMOUS) {
			return new Author(null, ANONYMOUS_AUTHOR_NAME, null, AuthorState.ANONYMOUS);
		}
		var profile = post.author();
		var deleted = profile.status() == CommunityProfileEntity.Status.DELETED;
		return new Author(profile.id(), deleted ? DELETED_AUTHOR_NAME : profile.displayName(),
				deleted ? null : profile.avatarPreset(),
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
