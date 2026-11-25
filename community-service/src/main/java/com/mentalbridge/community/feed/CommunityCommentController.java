package com.mentalbridge.community.feed;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

import com.mentalbridge.community.feed.CommunityCommentRequests.CreateCommentRequest;
import com.mentalbridge.community.feed.CommunityCommentRequests.UpdateCommentRequest;
import com.mentalbridge.community.feed.CommunityCommentResponses.Comment;
import com.mentalbridge.community.feed.CommunityCommentResponses.Page;
import com.mentalbridge.community.shared.CommunityApiException;

@Validated
@RestController
@RequestMapping("/api/v1/community")
class CommunityCommentController {

	private final CommunityCommentService comments;

	CommunityCommentController(CommunityCommentService comments) {
		this.comments = comments;
	}

	@GetMapping("/posts/{postId}/comments")
	Page list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
			@RequestParam(required = false) @Size(max = 256) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return comments.list(subject(jwt), postId, cursor, limit);
	}

	@PostMapping("/posts/{postId}/comments")
	ResponseEntity<Comment> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody CreateCommentRequest request) {
		var result = comments.create(subject(jwt), postId, idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).eTag(Long.toString(result.version())).body(result.body());
	}

	@PatchMapping("/comments/{commentId}")
	ResponseEntity<Comment> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId,
			@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
			@Valid @RequestBody UpdateCommentRequest request) {
		var result = comments.update(subject(jwt), commentId, parseVersion(ifMatch), request);
		return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result.body());
	}

	@DeleteMapping("/comments/{commentId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId,
			@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
		comments.delete(subject(jwt), commentId, parseVersion(ifMatch));
		return ResponseEntity.noContent().build();
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
