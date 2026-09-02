package com.mentalbridge.care.profile;

import java.time.Clock;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.shared.ApiException;

@Service
public class ProfileService {

	private final UserProfileRepository profiles;
	private final Clock clock;

	public ProfileService(UserProfileRepository profiles, Clock clock) {
		this.profiles = profiles;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ProfileView get(UUID accountId) {
		return view(profiles.findById(accountId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"PROFILE_NOT_FOUND", "Care profile was not found")));
	}

	@Transactional
	public SavedProfile put(UUID accountId, Long expectedVersion, ProfileCommand command) {
		validateTimezone(command.timezone());
		var existing = profiles.findByIdForUpdate(accountId);
		if (existing.isEmpty()) {
			if (expectedVersion != null) {
				throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PROFILE_VERSION_MISMATCH",
						"A new profile must not include If-Match");
			}
			return new SavedProfile(view(profiles.saveAndFlush(new UserProfileEntity(accountId, command, clock.instant()))), true);
		}
		var profile = existing.orElseThrow();
		if (expectedVersion == null) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PROFILE_VERSION_REQUIRED",
					"If-Match is required when replacing an existing profile");
		}
		if (profile.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PROFILE_VERSION_MISMATCH",
					"Care profile has changed since it was read");
		}
		profile.update(command, clock.instant());
		return new SavedProfile(view(profiles.saveAndFlush(profile)), false);
	}

	private void validateTimezone(String timezone) {
		try {
			ZoneId.of(timezone.strip());
		}
		catch (DateTimeException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Profile timezone is invalid",
					java.util.List.of(new ApiException.FieldViolation("timezone", "INVALID_TIMEZONE",
							"Timezone must be an IANA identifier")));
		}
	}

	private ProfileView view(UserProfileEntity profile) {
		return new ProfileView(profile.accountId(), profile.displayName(), profile.dateOfBirth(), profile.gender(),
				profile.locale(), profile.timezone(), profile.reminderEnabled(), profile.createdAt(), profile.updatedAt(),
				profile.version());
	}

	public record ProfileCommand(String displayName, LocalDate dateOfBirth, String gender, String locale,
			String timezone, boolean reminderEnabled) {
	}

	public record ProfileView(UUID accountId, String displayName, LocalDate dateOfBirth, String gender, String locale,
			String timezone, boolean reminderEnabled, java.time.Instant createdAt, java.time.Instant updatedAt,
			long version) {
	}

	public record SavedProfile(ProfileView profile, boolean created) {
	}
}
