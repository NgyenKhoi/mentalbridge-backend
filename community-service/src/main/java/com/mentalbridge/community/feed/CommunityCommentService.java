package com.mentalbridge.community.feed;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.community.feed.CommunityCommentRequests.CreateCommentRequest;
import com.mentalbridge.community.feed.CommunityCommentRequests.UpdateCommentRequest;
import com.mentalbridge.community.feed.CommunityCommentResponses.Comment;
import com.mentalbridge.community.feed.CommunityCommentResponses.Page;
import com.mentalbridge.community.feed.CommunityCommentResponses.VersionedComment;
import com.mentalbridge.community.feed.CommunityResponses.Author;
import com.mentalbridge.community.feed.CommunityResponses.AuthorState;
import com.mentalbridge.community.shared.CommunityApiException;

@Service
class CommunityCommentService {

	private static final String DEFAULT_DISPLAY_NAME = "Thành viên MentalBridge";
	private static final String DELETED_AUTHOR_NAME = "Thành viên đã rời cộng đồng";
	private static final String DELETED_COMMENT_CONTENT = "Bình luận đã được người viết xóa.";

	private final CommunityProfileRepository profiles;
	private final CommunityPostRepository posts;
	private final CommunityCommentRepository comments;
	private final CommunityCommentRevisionRepository revisions;

	CommunityCommentService(CommunityProfileRepository profiles, CommunityPostRepository posts,
			CommunityCommentRepository comments, CommunityCommentRevisionRepository revisions) {
		this.profiles = profiles;
		this.posts = posts;
		this.comments = comments;
		this.revisions = revisions;
	}

	@Transactional(readOnly = true)
	Page list(UUID subject, UUID postId, String cursorValue, int limit) {
		var viewerProfileId = profiles.findIdByAccountSubject(subject).orElse(null);
		posts.findVisibleById(postId, viewerProfileId).orElseThrow(CommunityApiException::postNotFound);
		var cursor = decode(cursorValue);
		var page = comments.findVisiblePage(postId, viewerProfileId,
				cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.commentId(),
				PageRequest.of(0, limit + 1));
		var hasMore = page.size() > limit;
		var visible = hasMore ? page.subList(0, limit) : page;
		var items = visible.stream().map(this::response).toList();
		var nextCursor = hasMore ? encode(visible.getLast().createdAt(), visible.getLast().id()) : null;
		return new Page(items, nextCursor, hasMore);
	}

	@Transactional
	VersionedComment create(UUID subject, UUID postId, String idempotencyKey, CreateCommentRequest request) {
		var key = validateIdempotencyKey(idempotencyKey);
		var content = validateContent(request.content());
		var now = Instant.now();
		profiles.createIfAbsent(UUID.randomUUID(), subject, DEFAULT_DISPLAY_NAME, now);
		var author = profiles.findByAccountSubjectForUpdate(subject)
				.filter(profile -> profile.status() == CommunityProfileEntity.Status.ACTIVE)
				.orElseThrow(CommunityApiException::communityAccessUnavailable);
		var post = posts.findVisibleByIdForUpdate(postId, author.id())
				.orElseThrow(CommunityApiException::postNotFound);
		var fingerprint = fingerprint(postId, request.parentCommentId(), content);
		var replay = comments.findByAuthorAndIdempotencyKeyForUpdate(author.id(), key);
		if (replay.isPresent()) {
			if (!fingerprint.equals(replay.get().requestFingerprint())) {
				throw CommunityApiException.idempotencyKeyReused();
			}
			if (replay.get().state() != CommunityCommentEntity.State.ACTIVE) {
				throw CommunityApiException.commentNotFound();
			}
			return versioned(replay.get());
		}
		var parent = request.parentCommentId() == null ? null
				: comments.findVisibleRootForUpdate(request.parentCommentId(), postId, author.id())
						.orElseThrow(CommunityApiException::commentNotFound);
		var comment = comments.saveAndFlush(new CommunityCommentEntity(UUID.randomUUID(), post, parent, author,
				content, key, fingerprint, now));
		post.addComment(now);
		revisions.save(new CommunityCommentRevisionEntity(UUID.randomUUID(), comment, author,
				CommunityCommentRevisionEntity.ChangeType.CREATED, content, comment.state(), comment.version(), now));
		comments.flush();
		return versioned(comment);
	}

	@Transactional
	VersionedComment update(UUID subject, UUID commentId, long expectedVersion, UpdateCommentRequest request) {
		var content = validateContent(request.content());
		var comment = owned(commentId, subject);
		verifyVersion(comment, expectedVersion);
		if (comment.content().equals(content)) {
			return versioned(comment);
		}
		var now = Instant.now();
		comment.edit(content, now);
		comments.flush();
		revisions.save(new CommunityCommentRevisionEntity(UUID.randomUUID(), comment, comment.author(),
				CommunityCommentRevisionEntity.ChangeType.EDITED, comment.content(), comment.state(), comment.version(), now));
		revisions.flush();
		return versioned(comment);
	}

	@Transactional
	void delete(UUID subject, UUID commentId, long expectedVersion) {
		var comment = owned(commentId, subject);
		verifyVersion(comment, expectedVersion);
		var now = Instant.now();
		comment.delete(now);
		comment.post().removeComment(now);
		comments.flush();
		revisions.save(new CommunityCommentRevisionEntity(UUID.randomUUID(), comment, comment.author(),
				CommunityCommentRevisionEntity.ChangeType.OWNER_DELETED, comment.content(), comment.state(),
				comment.version(), now));
		revisions.flush();
	}

	private CommunityCommentEntity owned(UUID commentId, UUID subject) {
		return comments.findOwnedActiveVisibleForUpdate(commentId, subject)
				.orElseThrow(CommunityApiException::commentNotFound);
	}

	private void verifyVersion(CommunityCommentEntity comment, long expectedVersion) {
		if (comment.version() != expectedVersion) {
			throw CommunityApiException.commentVersionMismatch();
		}
	}

	private String validateContent(String value) {
		var content = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
		if (content.isEmpty() || content.codePointCount(0, content.length()) > 2000
				|| hasUnpairedSurrogate(content)) {
			throw CommunityApiException.invalidCommentInput();
		}
		return content;
	}

	private String validateIdempotencyKey(String value) {
		if (value == null || value.length() < 16 || value.length() > 128
				|| value.chars().anyMatch(character -> character < 33 || character > 126)) {
			throw CommunityApiException.invalidIdempotencyKey();
		}
		return value;
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

	private String fingerprint(UUID postId, UUID parentCommentId, String content) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			add(digest, postId.toString());
			add(digest, parentCommentId == null ? "" : parentCommentId.toString());
			add(digest, content);
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

	private Comment response(CommunityCommentEntity comment) {
		var deleted = comment.state() == CommunityCommentEntity.State.OWNER_DELETED;
		return new Comment(comment.id(), comment.post().id(), comment.parent() == null ? null : comment.parent().id(),
				author(comment.author()), deleted ? DELETED_COMMENT_CONTENT : comment.content(), comment.state(),
				comment.version(), comment.createdAt(), comment.updatedAt());
	}

	private Author author(CommunityProfileEntity profile) {
		var deleted = profile.status() == CommunityProfileEntity.Status.DELETED;
		return new Author(profile.id(), deleted ? DELETED_AUTHOR_NAME : profile.displayName(),
				deleted ? null : profile.avatarPreset(), deleted ? AuthorState.DELETED : AuthorState.ACTIVE);
	}

	private VersionedComment versioned(CommunityCommentEntity comment) {
		return new VersionedComment(response(comment), comment.version());
	}

	private String encode(Instant createdAt, UUID commentId) {
		var value = createdAt + "|" + commentId;
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
				throw CommunityApiException.invalidCommentCursor();
			}
			return new Cursor(Instant.parse(decoded.substring(0, separator)),
					UUID.fromString(decoded.substring(separator + 1)));
		}
		catch (IllegalArgumentException exception) {
			throw CommunityApiException.invalidCommentCursor();
		}
	}

	private record Cursor(Instant createdAt, UUID commentId) {
	}
}
