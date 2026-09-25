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

@Service
public class AppointmentService {

	private static final Duration MINIMUM_LEAD_TIME = Duration.ofHours(4);
	private static final Duration DECISION_WINDOW = Duration.ofHours(24);
	private static final Duration DECISION_BUFFER = Duration.ofHours(2);
	private static final int LIST_LIMIT = 100;

	private final JdbcClient jdbc;
	private final ServiceCreditService credits;
	private final AvailabilityProperties availability;
	private final Clock clock;

	public AppointmentService(JdbcClient jdbc, ServiceCreditService credits,
			AvailabilityProperties availability, Clock clock) {
		this.jdbc = jdbc;
		this.credits = credits;
		this.availability = availability;
		this.clock = clock;
	}

	@Transactional
	public AppointmentResponse request(UUID userId, String idempotencyKey, UUID slotId,
			AppointmentModality requestedModality, UUID replacesAppointmentId) {
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
		var replacement = replacesAppointmentId == null ? null : lockReplacement(userId, replacesAppointmentId);
		if (replacement == null && creditAccount.reservationCapacity().remaining() == 0) {
			throw conflict("APPOINTMENT_RESERVATION_LIMIT_REACHED",
					"The package active appointment reservation limit has been reached");
		}
		var slot = lockSlot(slotId);
		if (!slot.status().equals("ACTIVE")) throw conflict("APPOINTMENT_SLOT_STALE", "The slot is no longer selectable");
		if (slot.modality() != requestedModality) throw conflict("APPOINTMENT_MODALITY_MISMATCH", "The requested modality does not match the slot");
		if (requestedModality == AppointmentModality.IN_APP_VIDEO && !availability.videoEnabled()) {
			throw conflict("APPOINTMENT_VIDEO_DISABLED", "In-app video appointments are not enabled");
		}
		if (slot.startAt().isBefore(now.plus(MINIMUM_LEAD_TIME))) {
			throw conflict("APPOINTMENT_LEAD_TIME_INVALID", "Appointments require at least four hours lead time");
		}
		if (activeAppointmentExists(slotId)) throw conflict("APPOINTMENT_SLOT_UNAVAILABLE", "The slot is already held");
		var credit = replacement == null ? lockCredit(userId, slot.startAt(), now) : replacement.creditId();
		if (replacement != null && (replacement.periodStart().isAfter(now)
				|| !replacement.periodEnd().isAfter(slot.startAt()))) {
			throw conflict("APPOINTMENT_CREDIT_UNAVAILABLE", "The replacement credit does not cover this appointment");
		}
		var appointmentId = UUID.randomUUID();
		var deadline = earlier(now.plus(DECISION_WINDOW), slot.startAt().minus(DECISION_BUFFER));
		try {
			if (replacement != null) {
				jdbc.sql("""
						update appointment set status='CANCELLED', updated_at=:now, version=version+1
						where id=:id and status in ('REQUESTED', 'CONFIRMED', 'IN_PROGRESS')
						""").param("now", database(now)).param("id", replacement.appointmentId()).update();
				credits.transition(userId, credit, replacement.appointmentId(), CreditEventType.RELEASED,
						"reschedule-release:" + appointmentId);
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
		}
		catch (DataIntegrityViolationException exception) {
			throw conflict("APPOINTMENT_SLOT_UNAVAILABLE", "The slot or credit is already held");
		}
		return findById(userId, appointmentId);
	}

	@Transactional(readOnly = true)
	public AppointmentResponse.ListResponse list(UUID userId) {
		var items = jdbc.sql("""
				select a.*, p.display_name from appointment a
				join specialist_profile p on p.account_id=a.specialist_account_id
				where a.user_account_id=:userId
				order by a.scheduled_start_at desc, a.id desc limit :limit
				""").param("userId", userId).param("limit", LIST_LIMIT).query(this::map).list();
		return new AppointmentResponse.ListResponse(items, items.size(), clock.instant());
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

	private Slot lockSlot(UUID slotId) {
		return jdbc.sql("""
				select specialist_account_id, start_at, end_at, timezone, modality, status
				from availability_slot where id=:slotId for update
				""").param("slotId", slotId).query((row, ignored) -> new Slot(
				row.getObject("specialist_account_id", UUID.class), row.getTimestamp("start_at").toInstant(),
				row.getTimestamp("end_at").toInstant(), row.getString("timezone"),
				AppointmentModality.valueOf(row.getString("modality")), row.getString("status"))).optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_SLOT_NOT_FOUND", "The slot was not found"));
	}

	private Replacement lockReplacement(UUID userId, UUID appointmentId) {
		var replacement = jdbc.sql("""
				select a.id, a.service_credit_id, a.status, p.period_start, p.period_end
				from appointment a
				join service_credit c on c.id=a.service_credit_id
				join service_credit_period p on p.id=c.period_id
				where a.id=:appointmentId and a.user_account_id=:userId
				for update of a
				""").param("appointmentId", appointmentId).param("userId", userId)
				.query((row, ignored) -> new Replacement(row.getObject("id", UUID.class),
						row.getObject("service_credit_id", UUID.class), row.getString("status"),
						row.getTimestamp("period_start").toInstant(), row.getTimestamp("period_end").toInstant()))
				.optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
						"APPOINTMENT_REPLACEMENT_NOT_FOUND", "The appointment to replace was not found"));
		if (!List.of("REQUESTED", "CONFIRMED", "IN_PROGRESS").contains(replacement.status())) {
			throw conflict("APPOINTMENT_REPLACEMENT_NOT_ACTIVE", "Only an active reservation can be replaced");
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
		return jdbc.sql("""
				select a.*, p.display_name from appointment a join specialist_profile p on p.account_id=a.specialist_account_id
				where a.user_account_id=:userId and a.idempotency_key=:key
				""").param("userId", userId).param("key", key).query(this::map).optional().orElse(null);
	}

	private AppointmentResponse findById(UUID userId, UUID id) {
		return jdbc.sql("""
				select a.*, p.display_name from appointment a join specialist_profile p on p.account_id=a.specialist_account_id
				where a.user_account_id=:userId and a.id=:id
				""").param("userId", userId).param("id", id).query(this::map).single();
	}

	private AppointmentResponse map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
		return new AppointmentResponse(row.getObject("id", UUID.class), row.getObject("availability_slot_id", UUID.class),
				row.getObject("specialist_account_id", UUID.class), row.getString("display_name"), row.getString("status"),
				AppointmentModality.valueOf(row.getString("modality")), row.getTimestamp("scheduled_start_at").toInstant(),
				row.getTimestamp("scheduled_end_at").toInstant(), row.getString("display_timezone"),
				row.getTimestamp("requested_at").toInstant(), row.getTimestamp("decision_deadline_at").toInstant(),
				row.getObject("service_credit_id", UUID.class), row.getObject("replaces_appointment_id", UUID.class));
	}

	private Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
	private OffsetDateTime database(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
	private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
	private record Slot(UUID specialistId, Instant startAt, Instant endAt, String timezone,
			AppointmentModality modality, String status) { }
	private record Replacement(UUID appointmentId, UUID creditId, String status, Instant periodStart,
			Instant periodEnd) { }
}
