package com.mentalbridge.care.supportplan;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;

@Service
public class SupportPlanPersonalizationService {

	public static final String SCHEMA_VERSION = "support-plan-personalization-v1";

	private final SupportPlanPersonalizationRepository personalizations;
	private final SupportPlanPersonalizationActivityModeRepository activityModes;
	private final SupportPlanPersonalizationBarrierRepository barriers;
	private final SupportPlanRepository plans;
	private final UserProfileRepository profiles;
	private final Clock clock;

	public SupportPlanPersonalizationService(SupportPlanPersonalizationRepository personalizations,
			SupportPlanPersonalizationActivityModeRepository activityModes,
			SupportPlanPersonalizationBarrierRepository barriers, SupportPlanRepository plans,
			UserProfileRepository profiles, Clock clock) {
		this.personalizations = personalizations;
		this.activityModes = activityModes;
		this.barriers = barriers;
		this.plans = plans;
		this.profiles = profiles;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PersonalizationView current(UUID userId) {
		requireProfile(userId);
		return personalizations.findFirstByUserIdOrderByRevisionDesc(userId)
				.map(this::view)
				.orElseGet(() -> PersonalizationView.notProvided(planReview(userId, null)));
	}

	@Transactional
	public PersonalizationView replace(UUID userId, long expectedRevision, PersonalizationCommand command) {
		validate(command);
		if (profiles.findByIdForUpdate(userId).isEmpty()) {
			throw profileNotFound();
		}
		var current = personalizations.findFirstByUserIdOrderByRevisionDesc(userId);
		long revision = current.map(SupportPlanPersonalizationEntity::revision).orElse(0L);
		if (revision != expectedRevision) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED,
					"SUPPORT_PLAN_PERSONALIZATION_VERSION_MISMATCH",
					"SupportPlan personalization has changed since it was read");
		}
		if (current.isPresent() && same(current.orElseThrow(), command)) {
			return view(current.orElseThrow());
		}

		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		var entity = personalizations.saveAndFlush(new SupportPlanPersonalizationEntity(
				UUID.randomUUID(), userId, revision + 1, command, now));
		for (int index = 0; index < command.preferredActivityModes().size(); index++) {
			activityModes.save(new SupportPlanPersonalizationActivityModeEntity(
					entity.id(), index + 1, command.preferredActivityModes().get(index)));
		}
		activityModes.flush();
		for (int index = 0; index < command.commonBarriers().size(); index++) {
			barriers.save(new SupportPlanPersonalizationBarrierEntity(
					entity.id(), index + 1, command.commonBarriers().get(index)));
		}
		barriers.flush();
		return view(entity);
	}

	@Transactional(readOnly = true)
	public Optional<PersonalizationSnapshot> currentSnapshot(UUID userId) {
		return personalizations.findFirstByUserIdOrderByRevisionDesc(userId).map(this::snapshot);
	}

	@Transactional(readOnly = true)
	Optional<PersonalizationSnapshot> snapshot(UUID id, UUID userId, long revision) {
		return personalizations.findByIdAndUserIdAndRevision(id, userId, revision).map(this::snapshot);
	}

	private PersonalizationView view(SupportPlanPersonalizationEntity entity) {
		var snapshot = snapshot(entity);
		return new PersonalizationView(snapshot.personalizationId(), snapshot.schemaVersion(),
				snapshot.revision(), entity.status(), snapshot.preferredFocus(), snapshot.desiredPace(),
				snapshot.dailyTimeBudgetMinutes(), snapshot.preferredDaypart(),
				snapshot.preferredActivityModes(), snapshot.commonBarriers(), entity.submittedAt(),
				planReview(entity.userId(), snapshot));
	}

	private PersonalizationSnapshot snapshot(SupportPlanPersonalizationEntity entity) {
		return new PersonalizationSnapshot(entity.id(), entity.userId(), entity.schemaVersion(), entity.revision(),
				value(PreferredFocus.class, entity.preferredFocus()), value(DesiredPace.class, entity.desiredPace()),
				entity.dailyTimeBudgetMinutes() == null ? null : entity.dailyTimeBudgetMinutes().intValue(),
				value(PreferredDaypart.class, entity.preferredDaypart()),
				activityModes.findByPersonalizationIdOrderByOrdinal(entity.id()).stream()
						.map(value -> ActivityMode.valueOf(value.activityMode())).toList(),
				barriers.findByPersonalizationIdOrderByOrdinal(entity.id()).stream()
						.map(value -> CommonBarrier.valueOf(value.barrier())).toList(),
				entity.submittedAt());
	}

	private PlanReviewView planReview(UUID userId, PersonalizationSnapshot snapshot) {
		var currentPlan = plans.findByUserIdAndStatusIn(userId, List.of("ACTIVE", "PAUSED"));
		if (currentPlan.isEmpty()) return new PlanReviewView(false, null, null, null);
		var plan = currentPlan.orElseThrow();
		boolean required = snapshot != null && (plan.personalizationId() != null || snapshot.provided())
				&& (!Objects.equals(plan.personalizationId(), snapshot.personalizationId())
						|| !Objects.equals(plan.personalizationRevision(), snapshot.revision()));
		return new PlanReviewView(required, plan.id(), plan.version(), required
				? "PREFERENCES_CHANGED_AFTER_PLAN_ACTIVATION" : null);
	}

	private boolean same(SupportPlanPersonalizationEntity current, PersonalizationCommand command) {
		return Objects.equals(current.preferredFocus(), name(command.preferredFocus()))
				&& Objects.equals(current.desiredPace(), name(command.desiredPace()))
				&& Objects.equals(current.dailyTimeBudgetMinutes(), command.dailyTimeBudgetMinutes() == null
						? null : command.dailyTimeBudgetMinutes().shortValue())
				&& Objects.equals(current.preferredDaypart(), name(command.preferredDaypart()))
				&& activityModes.findByPersonalizationIdOrderByOrdinal(current.id()).stream()
						.map(SupportPlanPersonalizationActivityModeEntity::activityMode).toList()
						.equals(command.preferredActivityModes().stream().map(Enum::name).toList())
				&& barriers.findByPersonalizationIdOrderByOrdinal(current.id()).stream()
						.map(SupportPlanPersonalizationBarrierEntity::barrier).toList()
						.equals(command.commonBarriers().stream().map(Enum::name).toList());
	}

	private void validate(PersonalizationCommand command) {
		if (command.preferredActivityModes().size() > 5
				|| new HashSet<>(command.preferredActivityModes()).size() != command.preferredActivityModes().size()) {
			throw validation("preferredActivityModes", "INVALID_ACTIVITY_MODES",
					"Choose each supported activity mode at most once");
		}
		if (command.commonBarriers().size() > 3
				|| new HashSet<>(command.commonBarriers()).size() != command.commonBarriers().size()) {
			throw validation("commonBarriers", "INVALID_COMMON_BARRIERS",
					"Choose each supported barrier at most once");
		}
		if (command.dailyTimeBudgetMinutes() != null
				&& !List.of(5, 10, 15, 20, 30).contains(command.dailyTimeBudgetMinutes())) {
			throw validation("dailyTimeBudgetMinutes", "INVALID_TIME_BUDGET",
					"Daily time budget must be 5, 10, 15, 20, or 30 minutes");
		}
	}

	private void requireProfile(UUID userId) {
		if (!profiles.existsById(userId)) throw profileNotFound();
	}

	private ApiException profileNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Care profile was not found");
	}

	private ApiException validation(String field, String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
				"SupportPlan personalization is invalid",
				List.of(new ApiException.FieldViolation(field, code, message)));
	}

	private String name(Enum<?> value) { return value == null ? null : value.name(); }

	private <T extends Enum<T>> T value(Class<T> type, String value) {
		return value == null ? null : Enum.valueOf(type, value);
	}

	public enum PreferredFocus { ANXIETY, MOOD, SLEEP, MOTIVATION, UNDERSTANDING_EMOTIONS }
	public enum DesiredPace { GENTLE, BALANCED, ACTIVE }
	public enum PreferredDaypart { MORNING, AFTERNOON, EVENING, FLEXIBLE }
	public enum ActivityMode { READING, GUIDED_PRACTICE, JOURNALING, EMOTION_CHECK_IN, ROUTINE }
	public enum CommonBarrier { LOW_ENERGY, LIMITED_TIME, DIFFICULT_TO_START }

	public record PersonalizationCommand(PreferredFocus preferredFocus, DesiredPace desiredPace,
			Integer dailyTimeBudgetMinutes, PreferredDaypart preferredDaypart,
			List<ActivityMode> preferredActivityModes, List<CommonBarrier> commonBarriers) {
		public PersonalizationCommand {
			preferredActivityModes = preferredActivityModes == null ? List.of() : List.copyOf(preferredActivityModes);
			commonBarriers = commonBarriers == null ? List.of() : List.copyOf(commonBarriers);
		}
		boolean provided() {
			return preferredFocus != null || desiredPace != null || dailyTimeBudgetMinutes != null
					|| preferredDaypart != null || !preferredActivityModes.isEmpty() || !commonBarriers.isEmpty();
		}
	}

	public record PersonalizationSnapshot(UUID personalizationId, UUID userId, String schemaVersion, long revision,
			PreferredFocus preferredFocus, DesiredPace desiredPace, Integer dailyTimeBudgetMinutes,
			PreferredDaypart preferredDaypart, List<ActivityMode> preferredActivityModes,
			List<CommonBarrier> commonBarriers, Instant submittedAt) {
		boolean provided() {
			return preferredFocus != null || desiredPace != null || dailyTimeBudgetMinutes != null
					|| preferredDaypart != null || !preferredActivityModes.isEmpty() || !commonBarriers.isEmpty();
		}
	}

	public record PersonalizationView(UUID personalizationId, String schemaVersion, long revision, String status,
			PreferredFocus preferredFocus, DesiredPace desiredPace, Integer dailyTimeBudgetMinutes,
			PreferredDaypart preferredDaypart, List<ActivityMode> preferredActivityModes,
			List<CommonBarrier> commonBarriers, Instant submittedAt, PlanReviewView planReview) {
		static PersonalizationView notProvided(PlanReviewView review) {
			return new PersonalizationView(null, SCHEMA_VERSION, 0, "NOT_PROVIDED", null, null, null,
					null, List.of(), List.of(), null, review);
		}
	}

	public record PlanReviewView(boolean required, UUID supportPlanId, Long supportPlanVersion,
			String reasonCode) { }
}
