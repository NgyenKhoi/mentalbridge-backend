package com.mentalbridge.consultation.specialist;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class SpecialistProfileService {

	private static final Set<String> SUPPORTED_LANGUAGES = Set.of("vi", "en");

	private final SpecialistProfileRepository profiles;
	private final SpecialistProfileStatusHistoryRepository history;
	private final Clock clock;

	public SpecialistProfileService(SpecialistProfileRepository profiles,
			SpecialistProfileStatusHistoryRepository history, Clock clock) {
		this.profiles = profiles;
		this.history = history;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ProfileView getOwn(UUID accountId) {
		return view(find(accountId));
	}

	@Transactional
	public SavedProfile saveDraft(UUID accountId, Long expectedVersion, ProfileCommand command) {
		validate(command);
		var existing = profiles.findByIdForUpdate(accountId);
		if (existing.isEmpty()) {
			if (expectedVersion != null) throw versionMismatch("A new specialist profile must not include If-Match");
			var created = profiles.saveAndFlush(new SpecialistProfileEntity(accountId, command, clock.instant()));
			return new SavedProfile(view(created), true);
		}
		var profile = existing.orElseThrow();
		checkVersion(profile, expectedVersion);
		if (profile.approvalStatus() != SpecialistApprovalStatus.PENDING) {
			throw new ApiException(HttpStatus.CONFLICT, "SPECIALIST_PROFILE_NOT_EDITABLE",
					"Only a pending specialist profile can be edited in this flow");
		}
		profile.updateDraft(command, clock.instant());
		return new SavedProfile(view(profiles.saveAndFlush(profile)), false);
	}

	@Transactional
	public ProfileView submit(UUID accountId, long expectedVersion) {
		var profile = locked(accountId);
		checkVersion(profile, expectedVersion);
		if (profile.approvalStatus() != SpecialistApprovalStatus.PENDING) {
			throw new ApiException(HttpStatus.CONFLICT, "SPECIALIST_PROFILE_NOT_SUBMITTABLE",
					"Only a pending specialist profile can be submitted");
		}
		if (profile.submittedAt() == null) {
			var now = clock.instant();
			profile.submit(now);
			profiles.saveAndFlush(profile);
			history.saveAndFlush(new SpecialistProfileStatusHistoryEntity(accountId,
					SpecialistApprovalStatus.PENDING, accountId,
					SpecialistProfileStatusHistoryEntity.ActorRole.SPECIALIST, now));
		}
		return view(profile);
	}

	@Transactional(readOnly = true)
	public List<ProfileView> listPending(int limit) {
		return profiles.findSubmittedPending(PageRequest.of(0, limit)).stream().map(this::view).toList();
	}

	@Transactional(readOnly = true)
	public ProfileView getForAdmin(UUID accountId) {
		var profile = find(accountId);
		if (profile.approvalStatus() == SpecialistApprovalStatus.PENDING && profile.submittedAt() == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "SPECIALIST_PROFILE_NOT_FOUND",
					"Specialist profile was not found");
		}
		return view(profile);
	}

	@Transactional
	public ProfileView approve(UUID accountId, UUID adminAccountId, long expectedVersion) {
		var profile = locked(accountId);
		checkVersion(profile, expectedVersion);
		if (profile.approvalStatus() == SpecialistApprovalStatus.APPROVED) return view(profile);
		if (profile.approvalStatus() != SpecialistApprovalStatus.PENDING || profile.submittedAt() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "SPECIALIST_PROFILE_NOT_APPROVABLE",
					"Only a submitted pending specialist profile can be approved");
		}
		var now = clock.instant();
		profile.approve(adminAccountId, now);
		profiles.saveAndFlush(profile);
		history.saveAndFlush(new SpecialistProfileStatusHistoryEntity(accountId,
				SpecialistApprovalStatus.APPROVED, adminAccountId,
				SpecialistProfileStatusHistoryEntity.ActorRole.ADMIN, now));
		return view(profile);
	}

	private SpecialistProfileEntity find(UUID accountId) {
		return profiles.findById(accountId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"SPECIALIST_PROFILE_NOT_FOUND", "Specialist profile was not found"));
	}

	private SpecialistProfileEntity locked(UUID accountId) {
		return profiles.findByIdForUpdate(accountId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"SPECIALIST_PROFILE_NOT_FOUND", "Specialist profile was not found"));
	}

	private void checkVersion(SpecialistProfileEntity profile, Long expectedVersion) {
		if (expectedVersion == null) throw versionMismatch("If-Match is required for an existing specialist profile");
		if (profile.version() != expectedVersion) throw versionMismatch("Specialist profile changed since it was read");
	}

	private ApiException versionMismatch(String message) {
		return new ApiException(HttpStatus.PRECONDITION_FAILED, "SPECIALIST_PROFILE_VERSION_MISMATCH", message);
	}

	private void validate(ProfileCommand command) {
		try {
			ZoneId.of(command.timezone().strip());
		}
		catch (DateTimeException exception) {
			throw validation("timezone", "INVALID_TIMEZONE", "Timezone must be a valid IANA identifier");
		}
		if (!SUPPORTED_LANGUAGES.containsAll(command.languages())) {
			throw validation("languages", "UNSUPPORTED_LANGUAGE", "Supported languages are vi and en");
		}
	}

	private ApiException validation(String field, String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Specialist profile is invalid",
				List.of(new ApiException.FieldViolation(field, code, message)));
	}

	private ProfileView view(SpecialistProfileEntity profile) {
		var supportAreas = profile.supportAreas().stream().sorted(Comparator.comparing(Enum::name)).toList();
		var languages = profile.languages().stream().sorted().toList();
		return new ProfileView(profile.accountId(), profile.displayName(), profile.bio(), supportAreas, languages,
				profile.yearsOfExperience(), profile.timezone(), profile.approvalStatus(), profile.submittedAt(),
				profile.reviewedAt(), profile.reviewedBy(), profile.decisionReasonCode(), profile.createdAt(),
				profile.updatedAt(), profile.version());
	}

	public record ProfileCommand(String displayName, String bio, Set<SupportArea> supportAreas,
			Set<String> languages, int yearsOfExperience, String timezone) {
	}

	public record ProfileView(UUID accountId, String displayName, String bio, List<SupportArea> supportAreas,
			List<String> languages, int yearsOfExperience, String timezone,
			SpecialistApprovalStatus approvalStatus, java.time.Instant submittedAt, java.time.Instant reviewedAt,
			UUID reviewedBy, String decisionReasonCode, java.time.Instant createdAt, java.time.Instant updatedAt,
			long version) {
	}

	public record SavedProfile(ProfileView profile, boolean created) {
	}
}
