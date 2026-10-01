package com.mentalbridge.community.feed;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.community.feed.CommunityPostRequests.WritePostRequest;
import com.mentalbridge.community.feed.CommunityResponses.PostDetail;
import com.mentalbridge.community.shared.CommunityApiException;

@Service
public class CommunityPostService {

	private static final String DEFAULT_DISPLAY_NAME = "Thành viên MentalBridge";

	private final CommunityProfileRepository profiles;
	private final CommunityPostRepository posts;
	private final CommunityMediaRepository media;
	private final CommunityFeedService feed;

	public CommunityPostService(CommunityProfileRepository profiles, CommunityPostRepository posts,
			CommunityMediaRepository media, CommunityFeedService feed) {
		this.profiles = profiles;
		this.posts = posts;
		this.media = media;
		this.feed = feed;
	}

	@Transactional
	public VersionedPost create(UUID subject, String idempotencyKey, WritePostRequest request) {
		var key = validateIdempotencyKey(idempotencyKey);
		var input = validate(request, CommunityPostEntity.AuthorMode.PROFILE);
		var fingerprint = fingerprint(input, true);
		var legacyFingerprint = request.authorMode() == null ? fingerprint(input, false) : null;
		var now = Instant.now();
		profiles.createIfAbsent(UUID.randomUUID(), subject, DEFAULT_DISPLAY_NAME, now);
		var owner = profiles.findByAccountSubjectForUpdate(subject)
				.filter(profile -> profile.status() == CommunityProfileEntity.Status.ACTIVE)
				.orElseThrow(CommunityApiException::communityAccessUnavailable);

		var replay = posts.findByOwnerAndIdempotencyKeyForUpdate(owner.id(), key);
		if (replay.isPresent()) {
			var storedFingerprint = replay.get().requestFingerprint();
			if (!fingerprint.equals(storedFingerprint)
					&& (legacyFingerprint == null || !legacyFingerprint.equals(storedFingerprint))) {
				throw CommunityApiException.idempotencyKeyReused();
			}
			return versioned(replay.get());
		}

		var post = new CommunityPostEntity(UUID.randomUUID(), owner, input.content(), input.topics(), key,
				fingerprint, input.authorMode(), now);
		post = posts.saveAndFlush(post);
		replaceMedia(post, owner, input.mediaIds(), now);
		posts.flush();
		return versioned(post);
	}

	@Transactional
	public VersionedPost update(UUID subject, UUID postId, long expectedVersion, WritePostRequest request) {
		var post = owned(postId, subject);
		var input = validate(request, post.authorMode());
		verifyVersion(post, expectedVersion);
		var now = Instant.now();
		post.update(input.content(), input.topics(), input.authorMode(), now);
		replaceMedia(post, post.author(), input.mediaIds(), now);
		posts.flush();
		return versioned(post);
	}

	@Transactional
	public void delete(UUID subject, UUID postId, long expectedVersion) {
		var post = owned(postId, subject);
		verifyVersion(post, expectedVersion);
		var now = Instant.now();
		for (var item : new ArrayList<>(post.media())) {
			item.detach(now);
			post.removeMedia(item);
		}
		post.delete(now);
		posts.flush();
	}

	private CommunityPostEntity owned(UUID postId, UUID subject) {
		return posts.findOwnedActiveByIdForUpdate(postId, subject, CommunityPostEntity.State.ACTIVE)
				.orElseThrow(CommunityApiException::postNotFound);
	}

	private void verifyVersion(CommunityPostEntity post, long expectedVersion) {
		if (post.version() != expectedVersion) {
			throw CommunityApiException.versionMismatch();
		}
	}

	private void replaceMedia(CommunityPostEntity post, CommunityProfileEntity owner, List<UUID> requestedIds,
			Instant now) {
		var lockOrder = requestedIds.stream().sorted().toList();
		var requested = lockOrder.isEmpty() ? List.<CommunityMediaEntity>of()
				: media.findAllByIdForUpdate(lockOrder);
		if (requested.size() != requestedIds.size()) {
			throw CommunityApiException.mediaNotAttachable();
		}
		var byId = requested.stream().collect(java.util.stream.Collectors.toMap(CommunityMediaEntity::id, item -> item));
		var preservedNonReady = post.media().stream()
				.filter(existing -> existing.state() != CommunityMediaEntity.State.READY
						&& !byId.containsKey(existing.id()))
				.count();
		if (requestedIds.size() + preservedNonReady > 10) {
			throw CommunityApiException.mediaNotAttachable();
		}
		for (var item : requested) {
			var alreadyAttachedToPost = item.post() != null && item.post().id().equals(post.id());
			if (!item.owner().id().equals(owner.id())
					|| (!alreadyAttachedToPost && (item.state() != CommunityMediaEntity.State.READY || item.post() != null))) {
				throw CommunityApiException.mediaNotAttachable();
			}
		}
		for (var existing : new ArrayList<>(post.media())) {
			// Non-READY attachments are deliberately absent from the read projection. Their
			// omission from mediaIds therefore cannot mean that the owner asked to detach
			// them. Only an omitted READY attachment is an explicit removal.
			if (!byId.containsKey(existing.id()) && existing.state() == CommunityMediaEntity.State.READY) {
				existing.detach(now);
				post.removeMedia(existing);
			}
		}
		for (short position = 0; position < requestedIds.size(); position++) {
			var item = byId.get(requestedIds.get(position));
			item.attachTo(post, position, now);
			post.addMedia(item);
		}
	}

	private ValidatedPost validate(WritePostRequest request, CommunityPostEntity.AuthorMode fallbackAuthorMode) {
		var content = Normalizer.normalize(request.content(), Normalizer.Form.NFC).trim();
		if (content.isEmpty() || content.codePointCount(0, content.length()) > 5000 || hasUnpairedSurrogate(content)) {
			throw CommunityApiException.invalidPostInput();
		}
		var topics = new LinkedHashSet<>(request.topics());
		if (topics.size() != request.topics().size() || topics.isEmpty() || topics.size() > 3
				|| topics.contains(null)) {
			throw CommunityApiException.invalidPostInput();
		}
		var suppliedMediaIds = request.mediaIds();
		if (suppliedMediaIds.size() > 10 || suppliedMediaIds.stream().anyMatch(java.util.Objects::isNull)
				|| new LinkedHashSet<>(suppliedMediaIds).size() != suppliedMediaIds.size()) {
			throw CommunityApiException.invalidPostInput();
		}
		var mediaIds = List.copyOf(suppliedMediaIds);
		var authorMode = request.authorMode() == null ? fallbackAuthorMode : request.authorMode();
		return new ValidatedPost(content, topics, mediaIds, authorMode);
	}

	private boolean hasUnpairedSurrogate(String value) {
		for (var index = 0; index < value.length(); index++) {
			var current = value.charAt(index);
			if (Character.isHighSurrogate(current)) {
				if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
					return true;
				}
			}
			else if (Character.isLowSurrogate(current)) {
				return true;
			}
		}
		return false;
	}

	private String validateIdempotencyKey(String value) {
		if (value == null || value.length() < 16 || value.length() > 128
				|| value.chars().anyMatch(character -> character < 33 || character > 126)) {
			throw CommunityApiException.invalidIdempotencyKey();
		}
		return value;
	}

	private String fingerprint(ValidatedPost input, boolean includeAuthorMode) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			add(digest, input.content());
			input.topics().stream().sorted(Comparator.comparingInt(Enum::ordinal))
					.forEach(topic -> add(digest, topic.name()));
			input.mediaIds().forEach(id -> add(digest, id.toString()));
			if (includeAuthorMode) {
				add(digest, input.authorMode().name());
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private void add(MessageDigest digest, String value) {
		var bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	private VersionedPost versioned(CommunityPostEntity post) {
		return new VersionedPost(feed.toDetail(post), post.version());
	}

	private record ValidatedPost(String content, Set<CommunityTopic> topics, List<UUID> mediaIds,
			CommunityPostEntity.AuthorMode authorMode) {
	}

	public record VersionedPost(PostDetail body, long version) {
	}
}
