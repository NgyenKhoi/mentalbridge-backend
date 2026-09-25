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
	private final SpecialistSuspensionEffects suspensionEffects;
	private final Clock clock;

	public SpecialistProfileService(SpecialistProfileRepository profiles,
			SpecialistProfileStatusHistoryRepository history,
			SpecialistSuspensionEffects suspensionEffects, Clock clock) {
		this.profiles = profiles;
		this.history = history;
		this.suspensionEffects = suspensionEffects;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ProfileView getOwn(UUID accountId) {
		return view(find(accountId));
	}

	@Transactional
	public void requireApprovedForAvailability(UUID accountId) {
		requireApproved(accountId, "Only an approved specialist can publish availability");
	}

	@Transactional
	public void requireApprovedForBooking(UUID accountId) {
		requireApproved(accountId, "Only an approved specialist can receive appointment requests");
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
		if (profile.approvalStatus() != SpecialistApprovalStatus.PENDING
				&& profile.approvalStatus() != SpecialistApprovalStatus.REJECTED) {
			throw new ApiException(HttpStatus.CONFLICT, "SPECIALIST_PROFILE_NOT_EDITABLE",
					"Only a pending or rejected specialist profile can be edited in this flow");
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

	@Transactional
	public ProfileView resubmit(UUID accountId, long expectedVersion) {
		var profile = locked(accountId);
		checkVersion(profile, expectedVersion);
		if (profile.approvalStatus() != SpecialistApprovalStatus.REJECTED) {
			throw new ApiException(HttpStatus.CONFLICT, "SPECIALIST_PROFILE_NOT_RESUBMITTABLE",
					"Only a rejected specialist profile can be resubmitted");
		}
		var now = clock.instant();
		profile.resubmit(now);
		profiles.saveAndFlush(profile);
		history.saveAndFlush(new SpecialistProfileStatusHistoryEntity(accountId,
				SpecialistApprovalStatus.PENDING, accountId,
				SpecialistProfileStatusHistoryEntity.ActorRole.SPECIALIST, now));
		return view(profile);
	}

	@Transactional(readOnly = true)
	public List<ProfileView> listForAdmin(SpecialistApprovalStatus status, int limit) {
		var page = PageRequest.of(0, limit);
		var results = status == SpecialistApprovalStatus.PENDING
				? profiles.findSubmittedPending(page)
				: profiles.findByApprovalStatusOrderByUpdatedAtDescAccountIdAsc(status, page);
		return results.stream().map(this::view).toList();
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

	@Transactional
	public ProfileView reject(UUID accountId, UUID adminAccountId, long expectedVersion,
			SpecialistDecisionReasonCode reasonCode) {
		if (!reasonCode.isRejection()) throw reasonMismatch("rejection");
		var profile = locked(accountId);
		checkVersion(profile, expectedVersion);
		if (profile.approvalStatus() == SpecialistApprovalStatus.REJECTED
				&& profile.decisionReasonCode() == reasonCode) return view(profile);
		if (profile.approvalStatus() != SpecialistApprovalStatus.PENDING || profile.submittedAt() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "SPECIALIST_PROFILE_NOT_REJECTABLE",
					"Only a submitted pending specialist profile can be rejected");
		}
		var now = clock.instant();
		profile.reject(adminAccountId, reasonCode, now);
		profiles.saveAndFlush(profile);
		history.saveAndFlush(new SpecialistProfileStatusHistoryEntity(accountId,
				SpecialistApprovalStatus.REJECTED, adminAccountId,
				SpecialistProfileStatusHistoryEntity.ActorRole.ADMIN, reasonCode, now));
		return view(profile);
	}

	@Transactional
	public SuspensionResult suspend(UUID accountId, UUID adminAccountId, long expectedVersion,
			SpecialistDecisionReasonCode reasonCode) {
		if (!reasonCode.isSuspension()) throw reasonMismatch("suspension");
		var profile = locked(accountId);
		checkVersion(profile, expectedVersion);
		if (profile.approvalStatus() == SpecialistApprovalStatus.SUSPENDED
				&& profile.decisionReasonCode() == reasonCode) {
			return new SuspensionResult(view(profile), SpecialistSuspensionEffects.Effects.none());
		}
		if (profile.approvalStatus() != SpecialistApprovalStatus.APPROVED) {
			throw new ApiException(HttpStatus.CONFLICT, "SPECIALIST_PROFILE_NOT_SUSPENDABLE",
					"Only an approved specialist profile can be suspended");
		}
		var now = clock.instant();
		var effects = suspensionEffects.apply(accountId, adminAccountId, now);
		profile.suspend(adminAccountId, reasonCode, now);
		profiles.saveAndFlush(profile);
		history.saveAndFlush(new SpecialistProfileStatusHistoryEntity(accountId,
				SpecialistApprovalStatus.SUSPENDED, adminAccountId,
				SpecialistProfileStatusHistoryEntity.ActorRole.ADMIN, reasonCode, now));
		return new SuspensionResult(view(profile), effects);
	}

	@Transactional
	public ProfileView restore(UUID accountId, UUID adminAccountId, long expectedVersion) {
		var profile = locked(accountId);
		checkVersion(profile, expectedVersion);
		if (profile.approvalStatus() == SpecialistApprovalStatus.APPROVED) return view(profile);
		if (profile.approvalStatus() != SpecialistApprovalStatus.SUSPENDED) {
			throw new ApiException(HttpStatus.CONFLICT, "SPECIALIST_PROFILE_NOT_RESTORABLE",
					"Only a suspended specialist profile can be restored");
		}
		var now = clock.instant();
		profile.restore(adminAccountId, now);
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

	private void requireApproved(UUID accountId, String message) {
		var profile = locked(accountId);
		if (profile.approvalStatus() != SpecialistApprovalStatus.APPROVED) {
			throw new ApiException(HttpStatus.CONFLICT, "SPECIALIST_NOT_APPROVED", message);
		}
	}

	private ApiException reasonMismatch(String operation) {
		return validation("reasonCode", "INVALID_DECISION_REASON",
				"Reason code is not valid for specialist " + operation);
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
			UUID reviewedBy, SpecialistDecisionReasonCode decisionReasonCode,
			java.time.Instant createdAt, java.time.Instant updatedAt,
			long version) {
	}

	public record SavedProfile(ProfileView profile, boolean created) {
	}

	public record SuspensionResult(ProfileView profile, SpecialistSuspensionEffects.Effects effects) {
	}
}
