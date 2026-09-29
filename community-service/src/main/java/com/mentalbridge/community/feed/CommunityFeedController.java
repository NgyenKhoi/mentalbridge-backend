package com.mentalbridge.community.feed;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.community.feed.CommunityResponses.Feed;
import com.mentalbridge.community.feed.CommunityResponses.PostDetail;
import com.mentalbridge.community.feed.CommunityResponses.Topic;
import com.mentalbridge.community.shared.CommunityApiException;

@Validated
@RestController
@RequestMapping("/api/v1/community")
public class CommunityFeedController {

	private final CommunityFeedService feed;

	public CommunityFeedController(CommunityFeedService feed) {
		this.feed = feed;
	}

	@GetMapping("/feed")
	Feed feed(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) CommunityTopic topic,
			@RequestParam(required = false) @Size(max = 256) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return feed.feed(subject(jwt), topic, cursor, limit);
	}

	@GetMapping("/posts/{postId}")
	PostDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
		return feed.detail(subject(jwt), postId);
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
}
