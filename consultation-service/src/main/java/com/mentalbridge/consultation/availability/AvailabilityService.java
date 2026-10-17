package com.mentalbridge.consultation.availability;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.specialist.SpecialistProfileService;

@Service
public class AvailabilityService {

	private static final Duration SLOT_DURATION = Duration.ofMinutes(60);
	private static final Duration MAX_LIST_WINDOW = Duration.ofDays(366);
	private static final int MAX_LIST_SIZE = 500;

	private final AvailabilitySlotRepository slots;
	private final SpecialistProfileService profiles;
	private final AvailabilityProperties properties;
	private final Clock clock;

	public AvailabilityService(AvailabilitySlotRepository slots, SpecialistProfileService profiles,
			AvailabilityProperties properties, Clock clock) {
		this.slots = slots;
		this.profiles = profiles;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public SlotView publish(UUID specialistAccountId, String idempotencyKey, PublishCommand command) {
		var normalized = normalize(command);
		profiles.requireApprovedForAvailability(specialistAccountId);
		var replay = slots.findBySpecialistAccountIdAndIdempotencyKey(specialistAccountId, idempotencyKey);
		if (replay.isPresent()) {
			if (!matches(replay.orElseThrow(), normalized)) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"Idempotency-Key was already used for another availability command");
			}
			return view(replay.orElseThrow(), clock.instant());
		}
		if (slots.existsActiveOverlap(specialistAccountId, normalized.startAt(), normalized.endAt())) {
			throw conflict("AVAILABILITY_SLOT_OVERLAP", "Availability overlaps an active slot");
		}
		var now = clock.instant();
		try {
			var created = slots.saveAndFlush(new AvailabilitySlotEntity(UUID.randomUUID(), specialistAccountId,
					normalized.startAt(), normalized.endAt(), normalized.timezone(), normalized.modality(),
					idempotencyKey, now));
			return view(created, now);
		}
		catch (DataIntegrityViolationException exception) {
			throw conflict("AVAILABILITY_SLOT_OVERLAP", "Availability overlaps an active slot");
		}
	}

	@Transactional(readOnly = true)
	public SlotList list(UUID specialistAccountId, String fromValue, String toValue, boolean includeWithdrawn) {
		var now = clock.instant();
		var from = fromValue == null ? now.minus(7, ChronoUnit.DAYS) : parseUtc("from", fromValue);
		var to = toValue == null ? now.plus(90, ChronoUnit.DAYS) : parseUtc("to", toValue);
		if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_LIST_WINDOW) > 0) {
			throw validation("to", "INVALID_AVAILABILITY_RANGE",
					"Availability range must be positive and no longer than 366 days");
		}
		var items = slots.listOwned(specialistAccountId, from, to, includeWithdrawn,
				PageRequest.of(0, MAX_LIST_SIZE)).stream().map(slot -> view(slot, now)).toList();
		return new SlotList(items, now, properties.videoEnabled());
	}

	@Transactional
	public SlotView withdraw(UUID specialistAccountId, UUID slotId, long expectedVersion) {
		var slot = slots.findOwnedByIdForUpdate(slotId, specialistAccountId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AVAILABILITY_SLOT_NOT_FOUND",
						"Availability slot was not found"));
		if (slot.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "AVAILABILITY_SLOT_VERSION_MISMATCH",
					"Availability slot changed since it was read");
		}
		if (slot.status() == AvailabilitySlotStatus.WITHDRAWN) {
			throw conflict("AVAILABILITY_SLOT_WITHDRAWN", "Availability slot is already withdrawn");
		}
		var now = clock.instant();
		if (!slot.startAt().isAfter(now)) {
			throw conflict("AVAILABILITY_SLOT_STALE", "Started availability cannot be withdrawn");
		}
		slot.withdraw(now);
		return view(slots.saveAndFlush(slot), now);
	}

	private NormalizedCommand normalize(PublishCommand command) {
		var startAt = parseUtc("startAt", command.startAt());
		var endAt = parseUtc("endAt", command.endAt());
		if (!Duration.between(startAt, endAt).equals(SLOT_DURATION)) {
			throw validation("endAt", "INVALID_SLOT_DURATION", "Availability must last exactly 60 minutes");
		}
		if (!startAt.isAfter(clock.instant())) {
			throw validation("startAt", "AVAILABILITY_MUST_BE_FUTURE", "Availability must start in the future");
		}
		var timezone = command.timezone() == null ? "" : command.timezone().strip();
		try {
			ZoneId.of(timezone);
		}
		catch (DateTimeException exception) {
			throw validation("timezone", "INVALID_TIMEZONE", "Timezone must be a valid IANA identifier");
		}
		if (command.modality() == AvailabilityModality.IN_APP_VIDEO && !properties.videoEnabled()) {
			throw conflict("VIDEO_AVAILABILITY_DISABLED", "In-app video availability is not enabled");
		}
		return new NormalizedCommand(startAt, endAt, timezone, command.modality());
	}

	private Instant parseUtc(String field, String value) {
		try {
			if (value == null || !value.endsWith("Z")) throw new DateTimeParseException("UTC required", "", 0);
			return Instant.parse(value);
		}
		catch (DateTimeParseException exception) {
			throw validation(field, "INVALID_UTC_INSTANT", field + " must be an RFC 3339 UTC instant");
		}
	}

	private boolean matches(AvailabilitySlotEntity slot, NormalizedCommand command) {
		return slot.startAt().equals(command.startAt()) && slot.endAt().equals(command.endAt())
				&& slot.timezone().equals(command.timezone()) && slot.modality() == command.modality();
	}

	private SlotView view(AvailabilitySlotEntity slot, Instant now) {
		var readiness = slot.status() == AvailabilitySlotStatus.WITHDRAWN ? AvailabilityReadiness.WITHDRAWN
				: !slot.startAt().isAfter(now) ? AvailabilityReadiness.STARTED
				: slot.modality() == AvailabilityModality.IN_APP_VIDEO && !properties.videoEnabled()
						? AvailabilityReadiness.VIDEO_DISABLED : AvailabilityReadiness.AVAILABLE;
		return new SlotView(slot.id(), slot.startAt(), slot.endAt(), slot.timezone(), slot.modality(),
				slot.status().name(), readiness, slot.withdrawnAt(), slot.createdAt(), slot.updatedAt(), slot.version());
	}

	private ApiException validation(String field, String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Availability command is invalid",
				List.of(new ApiException.FieldViolation(field, code, message)));
	}

	private ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message);
	}

	public record PublishCommand(String startAt, String endAt, String timezone, AvailabilityModality modality) {
	}

	private record NormalizedCommand(Instant startAt, Instant endAt, String timezone,
			AvailabilityModality modality) {
	}

	public record SlotView(UUID id, Instant startAt, Instant endAt, String timezone,
			AvailabilityModality modality, String status, AvailabilityReadiness readiness, Instant withdrawnAt,
			Instant createdAt, Instant updatedAt, long version) {
	}

	public record SlotList(List<SlotView> items, Instant generatedAt, boolean videoPublishingEnabled) {
	}
}
