package com.mentalbridge.community.feed;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.community.feed.CommunityResponses.Feed;
import com.mentalbridge.community.feed.CommunityResponses.PostDetail;
import com.mentalbridge.community.feed.CommunityResponses.Topic;
import com.mentalbridge.community.feed.CommunityPostRequests.WritePostRequest;
import com.mentalbridge.community.shared.CommunityApiException;

@Validated
@RestController
@RequestMapping("/api/v1/community")
public class CommunityFeedController {

	private final CommunityFeedService feed;
	private final CommunityPostService posts;

	public CommunityFeedController(CommunityFeedService feed, CommunityPostService posts) {
		this.feed = feed;
		this.posts = posts;
	}

	@GetMapping("/feed")
	Feed feed(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false, name = "topic") @Size(max = 3) List<CommunityTopic> topics,
			@RequestParam(required = false) @Size(max = 256) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return feed.feed(subject(jwt), topics, cursor, limit);
	}

	@GetMapping("/saved-posts")
	Feed savedPosts(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) @Size(max = 256) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return feed.savedPosts(subject(jwt), cursor, limit);
	}

	@GetMapping("/posts/{postId}")
	ResponseEntity<PostDetail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
		var result = feed.detail(subject(jwt), postId);
		var response = ResponseEntity.ok();
		if (result.ownerVersion() != null) {
			response.eTag(Long.toString(result.ownerVersion()));
		}
		return response.body(result.body());
	}

	@PostMapping("/posts")
	ResponseEntity<PostDetail> create(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody WritePostRequest request) {
		var result = posts.create(subject(jwt), idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).eTag(Long.toString(result.version())).body(result.body());
	}

	@PatchMapping("/posts/{postId}")
	ResponseEntity<PostDetail> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
			@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
			@Valid @RequestBody WritePostRequest request) {
		var result = posts.update(subject(jwt), postId, parseVersion(ifMatch), request);
		return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result.body());
	}

	@DeleteMapping("/posts/{postId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
			@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
		posts.delete(subject(jwt), postId, parseVersion(ifMatch));
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/topics")
	List<Topic> topics() {
		return feed.topics();
	}

	private UUID subject(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw CommunityApiException.invalidSubject();
		}
	}

	private long parseVersion(String value) {
		if (value == null || !value.matches("\\\"(0|[1-9]\\d*)\\\"")) {
			throw CommunityApiException.invalidVersionHeader();
		}
		try {
			return Long.parseLong(value.substring(1, value.length() - 1));
		}
		catch (NumberFormatException exception) {
			throw CommunityApiException.invalidVersionHeader();
		}
	}
}
