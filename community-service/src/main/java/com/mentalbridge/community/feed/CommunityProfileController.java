package com.mentalbridge.community.feed;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.community.shared.CommunityApiException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/community/profile")
public class CommunityProfileController {

	private final CommunityProfileService profiles;

	public CommunityProfileController(CommunityProfileService profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	ResponseEntity<CommunityProfileResponse> get(@AuthenticationPrincipal Jwt jwt) {
		var profile = profiles.get(subject(jwt));
		return ResponseEntity.ok().eTag(Long.toString(profile.version()))
				.body(CommunityProfileResponse.from(profile));
	}

	@PutMapping
	ResponseEntity<CommunityProfileResponse> put(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestBody JsonNode body) {
		var request = request(body);
		var saved = profiles.put(subject(jwt), version(ifMatch),
				new CommunityProfileService.ProfileCommand(request.displayName(), request.avatarPreset()));
		var response = ResponseEntity.status(saved.created() ? 201 : 200)
				.eTag(Long.toString(saved.profile().version()));
		if (saved.created()) {
			response.location(URI.create("/api/v1/community/profile"));
		}
		return response.body(CommunityProfileResponse.from(saved.profile()));
	}

	private PutCommunityProfileRequest request(JsonNode body) {
		if (!body.isObject() || body.size() != 2 || !body.has("displayName") || !body.has("avatarPreset")
				|| !body.path("displayName").isTextual()) {
			throw CommunityApiException.invalidProfile("Profile request must contain only displayName and avatarPreset");
		}
		var avatar = body.get("avatarPreset");
		if (!avatar.isNull() && !avatar.isTextual()) {
			throw CommunityApiException.invalidProfile("avatarPreset must be null or a supported preset");
		}
		try {
			return new PutCommunityProfileRequest(body.path("displayName").textValue(),
					avatar.isNull() ? null : AvatarPreset.valueOf(avatar.textValue()));
		}
		catch (IllegalArgumentException exception) {
			throw CommunityApiException.invalidProfile("avatarPreset must be a supported preset");
		}
	}

	private Long version(String ifMatch) {
		if (ifMatch == null) return null;
		if (!ifMatch.matches("\"(0|[1-9][0-9]*)\"")) {
			throw CommunityApiException.invalidIfMatch();
		}
		try {
			return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
		}
		catch (NumberFormatException exception) {
			throw CommunityApiException.invalidIfMatch();
		}
	}

	private UUID subject(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw CommunityApiException.invalidSubject();
		}
	}

	public record PutCommunityProfileRequest(String displayName, AvatarPreset avatarPreset) {
	}

	public record CommunityProfileResponse(UUID communityProfileId, String displayName, AvatarPreset avatarPreset,
			CommunityProfileEntity.Status status, long version, java.time.Instant createdAt,
			java.time.Instant updatedAt) {
		static CommunityProfileResponse from(CommunityProfileService.ProfileView value) {
			return new CommunityProfileResponse(value.communityProfileId(), value.displayName(), value.avatarPreset(),
					value.status(), value.version(), value.createdAt(), value.updatedAt());
		}
	}
}
