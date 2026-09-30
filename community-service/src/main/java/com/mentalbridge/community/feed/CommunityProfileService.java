package com.mentalbridge.community.feed;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.community.shared.CommunityApiException;

@Service
public class CommunityProfileService {

	private static final int MAX_DISPLAY_NAME_CODE_POINTS = 80;

	private final CommunityProfileRepository profiles;
	private final Clock clock;

	public CommunityProfileService(CommunityProfileRepository profiles, Clock clock) {
		this.profiles = profiles;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ProfileView get(UUID accountSubject) {
		return view(profiles.findByAccountSubject(accountSubject)
				.orElseThrow(CommunityApiException::profileNotFound));
	}

	@Transactional
	public SavedProfile put(UUID accountSubject, Long expectedVersion, ProfileCommand command) {
		var displayName = displayName(command.displayName());
		var existing = profiles.findByAccountSubjectForUpdate(accountSubject);
		if (existing.isEmpty()) {
			if (expectedVersion != null) {
				throw CommunityApiException.profileVersionMismatch("A new Community profile must not include If-Match");
			}
			var now = clock.instant();
			var created = new CommunityProfileEntity(UUID.randomUUID(), accountSubject, displayName,
					command.avatarPreset(), now);
			return new SavedProfile(view(profiles.saveAndFlush(created)), true);
		}

		var profile = existing.orElseThrow();
		if (profile.status() != CommunityProfileEntity.Status.ACTIVE) {
			throw new CommunityApiException(HttpStatus.FORBIDDEN, "COMMUNITY_PROFILE_INACTIVE",
					"Community profile is not active");
		}
		if (expectedVersion == null) {
			throw CommunityApiException.profileVersionRequired();
		}
		if (profile.version() != expectedVersion) {
			throw CommunityApiException.profileVersionMismatch("Community profile has changed since it was read");
		}
		if (!profile.displayName().equals(displayName) || profile.avatarPreset() != command.avatarPreset()) {
			profile.update(displayName, command.avatarPreset(), clock.instant());
			profiles.saveAndFlush(profile);
		}
		return new SavedProfile(view(profile), false);
	}

	private String displayName(String value) {
		if (value == null) {
			throw CommunityApiException.invalidProfile("displayName is required");
		}
		var normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
		var length = normalized.codePointCount(0, normalized.length());
		if (length < 1 || length > MAX_DISPLAY_NAME_CODE_POINTS || normalized.codePoints().anyMatch(this::unsafeCodePoint)) {
			throw CommunityApiException.invalidProfile(
					"displayName must contain 1 to 80 visible characters without control or formatting characters");
		}
		return normalized;
	}

	private boolean unsafeCodePoint(int codePoint) {
		var type = Character.getType(codePoint);
		return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
				|| type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE;
	}

	private ProfileView view(CommunityProfileEntity profile) {
		return new ProfileView(profile.id(), profile.displayName(), profile.avatarPreset(), profile.status(),
				profile.version(), profile.createdAt(), profile.updatedAt());
	}

	public record ProfileCommand(String displayName, AvatarPreset avatarPreset) {
	}

	public record ProfileView(UUID communityProfileId, String displayName, AvatarPreset avatarPreset,
			CommunityProfileEntity.Status status, long version, Instant createdAt, Instant updatedAt) {
	}

	public record SavedProfile(ProfileView profile, boolean created) {
	}
}
