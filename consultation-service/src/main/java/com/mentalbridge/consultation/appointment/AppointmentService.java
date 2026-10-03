package com.mentalbridge.consultation.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.availability.AvailabilityProperties;
import com.mentalbridge.consultation.credits.CreditEventType;
import com.mentalbridge.consultation.credits.ServiceCreditService;
import com.mentalbridge.consultation.entitlement.ServicePackage;
import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.specialist.SpecialistProfileService;

@Service
public class AppointmentService {

	private static final Duration MINIMUM_LEAD_TIME = Duration.ofHours(4);
	private static final Duration DECISION_WINDOW = Duration.ofHours(24);
	private static final Duration DECISION_BUFFER = Duration.ofHours(2);
	private static final Duration EARLY_CANCELLATION_WINDOW = Duration.ofHours(24);
	private static final int LIST_LIMIT = 100;
	private static final String USER_CANCELLED = "USER_CANCELLED";
	private static final String USER_RESCHEDULED = "USER_RESCHEDULED";
	private static final String RELEASED = "RELEASED";
	private static final String FORFEITED = "FORFEITED";
	private static final String TRANSFERRED = "TRANSFERRED_TO_REPLACEMENT";

	private final JdbcClient jdbc;
	private final ServiceCreditService credits;
	private final SpecialistProfileService profiles;
	private final AvailabilityProperties availability;
	private final Clock clock;
	private final AppointmentStatusOutbox outbox;

	public AppointmentService(JdbcClient jdbc, ServiceCreditService credits, SpecialistProfileService profiles,
			AvailabilityProperties availability, Clock clock, AppointmentStatusOutbox outbox) {
		this.jdbc = jdbc;
		this.credits = credits;
		this.profiles = profiles;
		this.availability = availability;
		this.clock = clock;
		this.outbox = outbox;
	}

	@Transactional
	public AppointmentResponse request(UUID userId, String idempotencyKey, UUID slotId,
			AppointmentModality requestedModality, UUID replacesAppointmentId, Long expectedReplacementVersion) {
		var replay = findByCommand(userId, idempotencyKey);
		if (replay != null) {
			if (!replay.slotId().equals(slotId) || replay.modality() != requestedModality
					|| !java.util.Objects.equals(replay.replacesAppointmentId(), replacesAppointmentId)) {
				throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used for another appointment request");
			}
			return replay;
		}
		var now = clock.instant();
		var creditAccount = credits.current(userId);
		if (creditAccount.packageCode() == ServicePackage.FREE) {
			throw new ApiException(HttpStatus.FORBIDDEN, "PAID_PLAN_REQUIRED",
					"A PLUS or PREMIUM package is required to request an appointment");
		}
		var specialistId = specialistForSlot(slotId);
		profiles.requireApprovedForBooking(specialistId);
		var replacement = replacesAppointmentId == null ? null
				: lockReplacement(userId, replacesAppointmentId, expectedReplacementVersion, now);
		if (replacement == null && creditAccount.reservationCapacity().remaining() == 0) {
			throw conflict("APPOINTMENT_RESERVATION_LIMIT_REACHED",
					"The package active appointment reservation limit has been reached");
		}
		var slot = lockSlot(slotId, specialistId);
		if (!slot.status().equals("ACTIVE")) throw conflict("APPOINTMENT_SLOT_STALE", "The slot is no longer selectable");
		if (slot.modality() != requestedModality) throw conflict("APPOINTMENT_MODALITY_MISMATCH", "The requested modality does not match the slot");
		if (requestedModality == AppointmentModality.IN_APP_VIDEO && !availability.videoEnabled()) {
			throw conflict("APPOINTMENT_VIDEO_DISABLED", "In-app video appointments are not enabled");
		}
		if (slot.startAt().isBefore(now.plus(MINIMUM_LEAD_TIME))) {
			throw conflict("APPOINTMENT_LEAD_TIME_INVALID", "Appointments require at least four hours lead time");
		}
		if (activeAppointmentExists(slotId)) throw conflict("APPOINTMENT_SLOT_UNAVAILABLE", "The slot is already held");
		var replacementOutcome = replacement == null ? null : replacementOutcome(replacement, now);
		var credit = replacement == null || replacementOutcome.equals(FORFEITED)
				? lockCredit(userId, slot.startAt(), now) : replacement.creditId();
		if (replacement != null && !replacementOutcome.equals(FORFEITED)
				&& (replacement.periodStart().isAfter(now) || !replacement.periodEnd().isAfter(slot.startAt()))) {
			throw conflict("APPOINTMENT_CREDIT_UNAVAILABLE", "The replacement credit does not cover this appointment");
		}
		var appointmentId = UUID.randomUUID();
		var deadline = earlier(now.plus(DECISION_WINDOW), slot.startAt().minus(DECISION_BUFFER));
		try {
			if (replacement != null) {
				cancelForReplacement(userId, replacement, appointmentId, replacementOutcome, now);
			}
			jdbc.sql("""
					insert into appointment (
					 id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id,
					 status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
					 requested_at, decision_deadline_at, idempotency_key, replaces_appointment_id, created_at, updated_at
					) values (
					 :id, :userId, :specialistId, :slotId, :creditId,
					 'REQUESTED', :modality, :startAt, :endAt, :timezone,
					 :now, :deadline, :key, :replacesAppointmentId, :now, :now
					)
					""").param("id", appointmentId).param("userId", userId)
					.param("specialistId", slot.specialistId()).param("slotId", slotId).param("creditId", credit)
					.param("modality", requestedModality.name()).param("startAt", database(slot.startAt()))
					.param("endAt", database(slot.endAt())).param("timezone", slot.timezone())
					.param("now", database(now)).param("deadline", database(deadline)).param("key", idempotencyKey)
					.param("replacesAppointmentId", replacesAppointmentId).update();
			credits.transition(userId, credit, appointmentId, CreditEventType.HELD, "appointment-hold:" + appointmentId);
			insertHistory(appointmentId, null, "REQUESTED", userId, "APPOINTMENT_REQUESTED",
					"request:" + appointmentId, null, now);
			outbox.record(appointmentId, UUID.randomUUID(), now);
		}
		catch (DataIntegrityViolationException exception) {
			throw conflict("APPOINTMENT_SLOT_UNAVAILABLE", "The slot or credit is already held");
		}
		return findById(userId, appointmentId);
	}

	@Transactional
	public AppointmentResponse cancel(UUID userId, UUID appointmentId, long expectedVersion, String idempotencyKey) {
		var replay = cancellationReplay(userId, appointmentId, idempotencyKey);
		if (replay != null) return replay;
		var appointment = lockCancellation(userId, appointmentId);
		replay = cancellationReplay(userId, appointmentId, idempotencyKey);
		if (replay != null) return replay;
		if (appointment.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "APPOINTMENT_VERSION_MISMATCH",
					"The appointment version is stale");
		}
		if (!List.of("REQUESTED", "CONFIRMED").contains(appointment.status())) {
			throw conflict("APPOINTMENT_NOT_CANCELLATION_ELIGIBLE",
					"Only a future requested or confirmed appointment can be cancelled");
		}
		var now = clock.instant();
		if (!now.isBefore(appointment.scheduledStartAt())) {
			throw conflict("APPOINTMENT_CHANGE_WINDOW_CLOSED", "The appointment has already started");
		}
		var outcome = cancellationOutcome(appointment.status(), appointment.scheduledStartAt(), now);
		jdbc.sql("""
				update appointment
				set status='CANCELLED', cancelled_at=:now, cancelled_by=:userId,
				    cancellation_reason=:reason, cancellation_credit_outcome=:outcome,
				    updated_at=:now, version=version+1
				where id=:id and version=:version
				""").param("now", database(now)).param("userId", userId).param("reason", USER_CANCELLED)
				.param("outcome", outcome).param("id", appointmentId).param("version", appointment.version()).update();
		credits.transition(userId, appointment.creditId(), appointmentId,
				outcome.equals(RELEASED) ? CreditEventType.RELEASED : CreditEventType.FORFEITED,
				"appointment-cancel:" + appointmentId);
		insertHistory(appointmentId, appointment.status(), "CANCELLED", userId, USER_CANCELLED,
				idempotencyKey, outcome, now);
		outbox.record(appointmentId, UUID.randomUUID(), now);
		return findById(userId, appointmentId);
	}

	@Transactional(readOnly = true)
	public AppointmentResponse.ListResponse list(UUID userId) {
		var items = jdbc.sql("""
				select a.*, p.display_name, c.state as credit_state,
				       replacement.id as replaced_by_appointment_id
				from appointment a
				join specialist_profile p on p.account_id=a.specialist_account_id
				join service_credit c on c.id=a.service_credit_id
				left join appointment replacement on replacement.replaces_appointment_id=a.id
				where a.user_account_id=:userId
				order by a.scheduled_start_at desc, a.id desc limit :limit
				""").param("userId", userId).param("limit", LIST_LIMIT).query(AppointmentRowMapper::map).list();
		var withHistory = AppointmentHistoryReader.attach(jdbc, items);
		return new AppointmentResponse.ListResponse(withHistory, withHistory.size(), clock.instant());
	}

	@Transactional(readOnly = true)
	public AppointmentResponse.BookableSlotList bookableSlots(Instant from, Instant to) {
		var now = clock.instant();
		var minimumStart = now.plus(MINIMUM_LEAD_TIME);
		var lower = from == null || from.isBefore(minimumStart) ? minimumStart : from;
		var upper = to == null ? now.plus(Duration.ofDays(90)) : to;
		if (!upper.isAfter(lower) || Duration.between(lower, upper).compareTo(Duration.ofDays(90)) > 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BOOKABLE_SLOT_RANGE", "Slot range must be positive and no longer than 90 days");
		}
		var items = jdbc.sql("""
				select s.id, s.specialist_account_id, p.display_name, s.start_at, s.end_at, s.timezone, s.modality
				from availability_slot s join specialist_profile p on p.account_id=s.specialist_account_id
				where s.status='ACTIVE' and p.approval_status='APPROVED'
				and s.start_at>=:from and s.start_at<:to
				and not exists (select 1 from appointment a where a.availability_slot_id=s.id and a.status in ('REQUESTED','CONFIRMED','IN_PROGRESS'))
				and (s.modality<>'IN_APP_VIDEO' or :videoEnabled)
				order by s.start_at, s.id limit 200
				""").param("from", database(lower)).param("to", database(upper))
				.param("videoEnabled", availability.videoEnabled())
				.query((row, ignored) -> new AppointmentResponse.BookableSlot(row.getObject("id", UUID.class),
						row.getObject("specialist_account_id", UUID.class), row.getString("display_name"),
						row.getTimestamp("start_at").toInstant(), row.getTimestamp("end_at").toInstant(),
						row.getString("timezone"), AppointmentModality.valueOf(row.getString("modality")))).list();
		return new AppointmentResponse.BookableSlotList(items, items.size(), now, availability.videoEnabled());
	}

	private UUID specialistForSlot(UUID slotId) {
		return jdbc.sql("select specialist_account_id from availability_slot where id=:slotId")
				.param("slotId", slotId).query(UUID.class).optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_SLOT_NOT_FOUND", "The slot was not found"));
	}

	private Slot lockSlot(UUID slotId, UUID specialistId) {
		return jdbc.sql("""
				select specialist_account_id, start_at, end_at, timezone, modality, status
				from availability_slot where id=:slotId and specialist_account_id=:specialistId for update
				""").param("slotId", slotId).param("specialistId", specialistId).query((row, ignored) -> new Slot(
				row.getObject("specialist_account_id", UUID.class), row.getTimestamp("start_at").toInstant(),
				row.getTimestamp("end_at").toInstant(), row.getString("timezone"),
				AppointmentModality.valueOf(row.getString("modality")), row.getString("status"))).optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_SLOT_NOT_FOUND", "The slot was not found"));
	}

	private Replacement lockReplacement(UUID userId, UUID appointmentId, Long expectedVersion, Instant now) {
		if (expectedVersion == null) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "APPOINTMENT_VERSION_REQUIRED",
					"If-Match is required when replacing an appointment");
		}
		var replacement = jdbc.sql("""
				select a.id, a.service_credit_id, a.status, a.scheduled_start_at, a.version,
				       p.period_start, p.period_end
				from appointment a
				join service_credit c on c.id=a.service_credit_id
				join service_credit_period p on p.id=c.period_id
				where a.id=:appointmentId and a.user_account_id=:userId
				for update of a
				""").param("appointmentId", appointmentId).param("userId", userId)
				.query((row, ignored) -> new Replacement(row.getObject("id", UUID.class),
						row.getObject("service_credit_id", UUID.class), row.getString("status"),
						row.getTimestamp("scheduled_start_at").toInstant(), row.getLong("version"),
						row.getTimestamp("period_start").toInstant(), row.getTimestamp("period_end").toInstant()))
				.optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
						"APPOINTMENT_REPLACEMENT_NOT_FOUND", "The appointment to replace was not found"));
		if (replacement.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "APPOINTMENT_VERSION_MISMATCH",
					"The appointment version is stale");
		}
		if (!List.of("REQUESTED", "CONFIRMED").contains(replacement.status())) {
			throw conflict("APPOINTMENT_REPLACEMENT_NOT_ACTIVE", "Only an active reservation can be replaced");
		}
		if (!now.isBefore(replacement.scheduledStartAt())) {
			throw conflict("APPOINTMENT_CHANGE_WINDOW_CLOSED", "The appointment has already started");
		}
		return replacement;
	}

	private UUID lockCredit(UUID userId, Instant appointmentStart, Instant now) {
		return jdbc.sql("""
				select c.id from service_credit c join service_credit_period p on p.id=c.period_id
				where p.account_id=:userId and c.state='AVAILABLE'
				and p.period_start<=:now and p.period_end>:appointmentStart
				order by p.period_end, c.ordinal for update of c skip locked limit 1
				""").param("userId", userId).param("now", database(now))
				.param("appointmentStart", database(appointmentStart)).query(UUID.class).optional()
				.orElseThrow(() -> conflict("APPOINTMENT_CREDIT_UNAVAILABLE", "No available credit covers this appointment"));
	}

	private boolean activeAppointmentExists(UUID slotId) {
		return jdbc.sql("select exists(select 1 from appointment where availability_slot_id=:slotId and status in ('REQUESTED','CONFIRMED','IN_PROGRESS'))")
				.param("slotId", slotId).query(Boolean.class).single();
	}

	private AppointmentResponse findByCommand(UUID userId, String key) {
		var appointment = jdbc.sql("""
				select a.*, p.display_name, c.state as credit_state,
				       replacement.id as replaced_by_appointment_id
				from appointment a
				join specialist_profile p on p.account_id=a.specialist_account_id
				join service_credit c on c.id=a.service_credit_id
				left join appointment replacement on replacement.replaces_appointment_id=a.id
				where a.user_account_id=:userId and a.idempotency_key=:key
				""").param("userId", userId).param("key", key).query(AppointmentRowMapper::map).optional().orElse(null);
		return appointment == null ? null : AppointmentHistoryReader.attach(jdbc, appointment);
	}

	private AppointmentResponse findById(UUID userId, UUID id) {
		var appointment = jdbc.sql("""
				select a.*, p.display_name, c.state as credit_state,
				       replacement.id as replaced_by_appointment_id
				from appointment a
				join specialist_profile p on p.account_id=a.specialist_account_id
				join service_credit c on c.id=a.service_credit_id
				left join appointment replacement on replacement.replaces_appointment_id=a.id
				where a.user_account_id=:userId and a.id=:id
				""").param("userId", userId).param("id", id).query(AppointmentRowMapper::map).single();
		return AppointmentHistoryReader.attach(jdbc, appointment);
	}

	private AppointmentResponse cancellationReplay(UUID userId, UUID appointmentId, String key) {
		var replay = jdbc.sql("""
				select exists(
				  select 1 from appointment_status_history history
				  join appointment appointment on appointment.id=history.appointment_id
				  where history.appointment_id=:appointmentId and history.idempotency_key=:key
				    and history.changed_by=:userId and history.reason=:reason
				    and appointment.user_account_id=:userId
				)
				""").param("appointmentId", appointmentId).param("key", key).param("userId", userId)
				.param("reason", USER_CANCELLED).query(Boolean.class).single();
		return replay ? findById(userId, appointmentId) : null;
	}

	private CancellationHold lockCancellation(UUID userId, UUID appointmentId) {
		return jdbc.sql("""
				select a.id, a.service_credit_id, a.status, a.scheduled_start_at, a.version
				from appointment a join service_credit credit on credit.id=a.service_credit_id
				where a.id=:id and a.user_account_id=:userId
				for update of a, credit
				""").param("id", appointmentId).param("userId", userId)
				.query((row, ignored) -> new CancellationHold(row.getObject("id", UUID.class),
						row.getObject("service_credit_id", UUID.class), row.getString("status"),
						row.getTimestamp("scheduled_start_at").toInstant(), row.getLong("version")))
				.optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND",
						"The appointment was not found"));
	}

	private String replacementOutcome(Replacement replacement, Instant now) {
		return cancellationOutcome(replacement.status(), replacement.scheduledStartAt(), now).equals(RELEASED)
				? TRANSFERRED : FORFEITED;
	}

	private String cancellationOutcome(String status, Instant scheduledStartAt, Instant now) {
		if (status.equals("REQUESTED")) return RELEASED;
		return !scheduledStartAt.isBefore(now.plus(EARLY_CANCELLATION_WINDOW)) ? RELEASED : FORFEITED;
	}

	private void cancelForReplacement(UUID userId, Replacement replacement, UUID replacementAppointmentId,
			String outcome, Instant now) {
		jdbc.sql("""
				update appointment
				set status='CANCELLED', cancelled_at=:now, cancelled_by=:userId,
				    cancellation_reason=:reason, cancellation_credit_outcome=:outcome,
				    updated_at=:now, version=version+1
				where id=:id and version=:version
				""").param("now", database(now)).param("userId", userId).param("reason", USER_RESCHEDULED)
				.param("outcome", outcome).param("id", replacement.appointmentId())
				.param("version", replacement.version()).update();
		credits.transition(userId, replacement.creditId(), replacement.appointmentId(),
				outcome.equals(FORFEITED) ? CreditEventType.FORFEITED : CreditEventType.RELEASED,
				"reschedule-settle:" + replacementAppointmentId);
		insertHistory(replacement.appointmentId(), replacement.status(), "CANCELLED", userId,
				USER_RESCHEDULED, "reschedule:" + replacementAppointmentId, outcome, now);
		outbox.record(replacement.appointmentId(), UUID.randomUUID(), now);
	}

	private void insertHistory(UUID appointmentId, String fromStatus, String toStatus, UUID actor, String reason,
			String idempotencyKey, String creditOutcome, Instant now) {
		jdbc.sql("""
				insert into appointment_status_history (
				 id, appointment_id, from_status, to_status, changed_by, reason,
				 idempotency_key, changed_at, credit_outcome
				) values (:id, :appointmentId, :fromStatus, :toStatus, :actor, :reason, :key, :now, :creditOutcome)
				""").param("id", UUID.randomUUID()).param("appointmentId", appointmentId)
				.param("fromStatus", fromStatus).param("toStatus", toStatus).param("actor", actor)
				.param("reason", reason).param("key", idempotencyKey).param("now", database(now))
				.param("creditOutcome", creditOutcome).update();
	}

	private Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
	private OffsetDateTime database(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
	private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
	private record Slot(UUID specialistId, Instant startAt, Instant endAt, String timezone,
			AppointmentModality modality, String status) { }
	private record Replacement(UUID appointmentId, UUID creditId, String status, Instant scheduledStartAt,
			long version, Instant periodStart, Instant periodEnd) { }
	private record CancellationHold(UUID appointmentId, UUID creditId, String status, Instant scheduledStartAt,
			long version) { }
}
