package com.mentalbridge.consultation.specialist;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

import static com.mentalbridge.consultation.specialist.ProfileAmendmentEntity.Status.*;

@Service
public class ProfileAmendmentService {

	private final SpecialistProfileRepository profiles;
	private final SpecialistProfileService profileService;
	private final ProfileAmendmentRepository amendments;
	private final ApprovedProfileVersionRepository approvedVersions;
	private final ProfileAmendmentHistoryRepository history;
	private final Clock clock;

	public ProfileAmendmentService(SpecialistProfileRepository profiles, SpecialistProfileService profileService,
			ProfileAmendmentRepository amendments, ApprovedProfileVersionRepository approvedVersions,
			ProfileAmendmentHistoryRepository history, Clock clock) {
		this.profiles = profiles;
		this.profileService = profileService;
		this.amendments = amendments;
		this.approvedVersions = approvedVersions;
		this.history = history;
		this.clock = clock;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Detail own(UUID actor) {
		var profile = profiles.findById(actor).orElseThrow(this::notFound);
		var amendment = amendments.findFirstBySpecialistAccountIdOrderByCreatedAtDescIdDesc(actor)
				.map(ProfileAmendmentResponse::from).orElse(null);
		return new Detail(SpecialistProfileResponse.from(profileService.view(profile)), amendment);
	}

	@Transactional
	public ProfileAmendmentResponse start(UUID actor, long expectedProfileVersion) {
		var profile = approvedLocked(actor);
		if (profile.version() != expectedProfileVersion) throw versionMismatch();
		var current = amendments.findFirstBySpecialistAccountIdOrderByCreatedAtDescIdDesc(actor);
		if (current.isPresent() && current.orElseThrow().status() != APPROVED) {
			return ProfileAmendmentResponse.from(current.orElseThrow());
		}
		return saved(new ProfileAmendmentEntity(profile, clock.instant()), actor, "SPECIALIST");
	}

	@Transactional
	public ProfileAmendmentResponse edit(UUID actor, UUID amendmentId, long expectedVersion,
			SpecialistProfileService.ProfileCommand command) {
		var profile = approvedLocked(actor);
		var amendment = owned(amendmentId, actor);
		checkVersion(amendment, expectedVersion);
		checkBase(profile, amendment);
		if (amendment.status() == APPROVED) throw stateConflict();
		profileService.validate(command);
		var normalized = new SpecialistProfileService.ProfileCommand(command.displayName().strip(), command.bio().strip(),
				command.supportAreas(), command.languages().stream().map(value -> value.strip().toLowerCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet()), command.yearsOfExperience(), command.timezone().strip());
		if (amendment.proposedProfile().equals(normalized)) return ProfileAmendmentResponse.from(amendment);
		amendment.edit(normalized, clock.instant());
		return saved(amendment, actor, "SPECIALIST");
	}

	@Transactional
	public ProfileAmendmentResponse submit(UUID actor, UUID amendmentId, long expectedVersion, boolean resubmit) {
		var profile = approvedLocked(actor);
		var amendment = owned(amendmentId, actor);
		checkVersion(amendment, expectedVersion);
		checkBase(profile, amendment);
		if (amendment.status() == PENDING_REVIEW) return ProfileAmendmentResponse.from(amendment);
		if (amendment.status() != (resubmit ? REJECTED : DRAFT)) throw stateConflict();
		profileService.validate(amendment.proposedProfile());
		amendment.submit(clock.instant());
		return saved(amendment, actor, "SPECIALIST");
	}

	@Transactional(readOnly = true)
	public Queue list(int limit, int page) {
		var results = amendments.findReviewable(PageRequest.of(page, limit));
		var items = results.getContent().stream().map(ProfileAmendmentResponse::from).toList();
		return new Queue(items, items.size(), results.hasNext());
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Detail detail(UUID amendmentId) {
		var amendment = amendments.findById(amendmentId).orElseThrow(this::notFound);
		var profile = profiles.findById(amendment.specialistAccountId()).orElseThrow(this::notFound);
		return new Detail(SpecialistProfileResponse.from(profileService.view(profile)), ProfileAmendmentResponse.from(amendment));
	}

	@Transactional
	public ProfileAmendmentResponse approve(UUID amendmentId, UUID admin, long expectedVersion) {
		var profile = approvedLocked(amendments.findOwnerById(amendmentId).orElseThrow(this::notFound));
		var amendment = owned(amendmentId, profile.accountId());
		checkVersion(amendment, expectedVersion);
		if (amendment.status() == APPROVED) return ProfileAmendmentResponse.from(amendment);
		checkBase(profile, amendment);
		if (amendment.status() != PENDING_REVIEW) throw stateConflict();
		var now = clock.instant();
		profile.promote(amendment.proposedProfile(), admin, now);
		profiles.saveAndFlush(profile);
		amendment.decide(APPROVED, admin, null, now);
		var result = saved(amendment, admin, "ADMIN");
		approvedVersions.saveAndFlush(new ApprovedProfileVersionEntity(profile, amendmentId));
		return result;
	}

	@Transactional
	public ProfileAmendmentResponse reject(UUID amendmentId, UUID admin, long expectedVersion,
			SpecialistDecisionReasonCode reason) {
		if (!reason.isRejection()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid rejection reason");
		var profile = approvedLocked(amendments.findOwnerById(amendmentId).orElseThrow(this::notFound));
		var amendment = owned(amendmentId, profile.accountId());
		checkVersion(amendment, expectedVersion);
		checkBase(profile, amendment);
		if (amendment.status() == REJECTED && amendment.reasonCode() == reason) return ProfileAmendmentResponse.from(amendment);
		if (amendment.status() != PENDING_REVIEW) throw stateConflict();
		amendment.decide(REJECTED, admin, reason, clock.instant());
		return saved(amendment, admin, "ADMIN");
	}

	private SpecialistProfileEntity approvedLocked(UUID actor) {
		var profile = profiles.findByIdForUpdate(actor).orElseThrow(this::notFound);
		if (profile.approvalStatus() != SpecialistApprovalStatus.APPROVED) {
			throw new ApiException(HttpStatus.CONFLICT, "SPECIALIST_NOT_APPROVED", "An active approved profile is required");
		}
		return profile;
	}

	private ProfileAmendmentEntity owned(UUID id, UUID owner) {
		return amendments.findById(id).filter(value -> value.specialistAccountId().equals(owner)).orElseThrow(this::notFound);
	}

	private void checkVersion(ProfileAmendmentEntity amendment, long expected) {
		if (amendment.version() != expected) throw versionMismatch();
	}

	private void checkBase(SpecialistProfileEntity profile, ProfileAmendmentEntity amendment) {
		if (profile.publishedVersion() != amendment.basePublishedVersion()) {
			throw new ApiException(HttpStatus.CONFLICT, "PROFILE_AMENDMENT_BASE_CHANGED", "The approved profile changed; reload before continuing");
		}
	}

	private ProfileAmendmentResponse saved(ProfileAmendmentEntity amendment, UUID actor, String role) {
		amendments.saveAndFlush(amendment);
		history.saveAndFlush(new ProfileAmendmentHistoryEntity(amendment, actor, role));
		return ProfileAmendmentResponse.from(amendment);
	}

	private ApiException versionMismatch() {
		return new ApiException(HttpStatus.PRECONDITION_FAILED, "PROFILE_AMENDMENT_VERSION_MISMATCH", "The profile or amendment changed; reload before continuing");
	}

	private ApiException stateConflict() {
		return new ApiException(HttpStatus.CONFLICT, "PROFILE_AMENDMENT_STATE_CONFLICT", "Amendment state does not allow this action");
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "PROFILE_AMENDMENT_NOT_FOUND", "Profile amendment was not found");
	}

	public record Detail(SpecialistProfileResponse approvedProfile, ProfileAmendmentResponse amendment) { }
	public record Queue(List<ProfileAmendmentResponse> items, int count, boolean hasMore) { }
}
