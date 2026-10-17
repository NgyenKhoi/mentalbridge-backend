package com.mentalbridge.community.feed;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.community.feed.CommunityInteractionService.CommunityReaction;
import com.mentalbridge.community.feed.CommunityPostReactionEntity.Reaction;
import com.mentalbridge.community.shared.CommunityApiException;

@RestController
@RequestMapping("/api/v1/community/posts/{postId}")
class CommunityInteractionController {

	private final CommunityInteractionService interactions;

	CommunityInteractionController(CommunityInteractionService interactions) {
		this.interactions = interactions;
	}

	@PutMapping("/reaction")
	CommunityReaction putReaction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
			@Valid @RequestBody PutReactionRequest request) {
		return interactions.putReaction(subject(jwt), postId, request.reaction());
	}

	@DeleteMapping("/reaction")
	ResponseEntity<Void> deleteReaction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
		interactions.deleteReaction(subject(jwt), postId);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/bookmark")
	ResponseEntity<Void> putBookmark(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
		interactions.putBookmark(subject(jwt), postId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/bookmark")
	ResponseEntity<Void> deleteBookmark(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
		interactions.deleteBookmark(subject(jwt), postId);
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

	record PutReactionRequest(@NotNull Reaction reaction) {
	}
}
