package com.mentalbridge.consultation.video;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.appointment.AppointmentStatusOutbox;
import com.mentalbridge.consultation.shared.ApiException;

@Service
class VideoSessionTransactions {

	static final String POLICY = "livekit-video-completion-v1";
	final VideoAppointmentRepository appointments;
	final VideoRoomRepository rooms;
	final EntityManager entities;
	final Clock clock;
	private final LiveKitProperties properties;
	private final VideoProvider provider;
	private final AppointmentStatusOutbox outbox;
	private final VideoConfirmationRepository confirmations;

	VideoSessionTransactions(VideoAppointmentRepository appointments, VideoRoomRepository rooms,
			EntityManager entities, Clock clock, LiveKitProperties properties, VideoProvider provider,
			AppointmentStatusOutbox outbox, VideoConfirmationRepository confirmations) {
		this.appointments = appointments;
		this.rooms = rooms;
		this.entities = entities;
		this.clock = clock;
		this.properties = properties;
		this.provider = provider;
		this.outbox = outbox;
		this.confirmations = confirmations;
	}

	@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public VideoSessionResponse session(UUID id, UUID actor, String role) {
		var appointment = authorized(id, actor, role, false);
		var now = clock.instant();
		var phase = "NOT_AVAILABLE";
		var active = eligible(appointment);
		var room = rooms.findById(id).orElse(null);
		if (room != null && !List.of("PREPARING", "OPEN").contains(room.state)) active = false;
		if ("COMPLETED".equals(appointment.status)) phase = "COMPLETED";
		else if ("SESSION_ENDED".equals(appointment.status) || !now.isBefore(appointment.endsAt)) phase = "ENDED";
		else if (active && (!properties.isEnabled() || !properties.configured())) phase = "UNAVAILABLE";
		else if (active && now.isBefore(appointment.startsAt.minusSeconds(300))) phase = "TOO_EARLY";
		else if (active && now.isBefore(appointment.startsAt)) phase = "WAITING";
		else if (active) phase = "ACTIVE";
		return new VideoSessionResponse(id, phase, List.of("WAITING", "ACTIVE").contains(phase),
				"ACTIVE".equals(phase), appointment.startsAt, appointment.endsAt, now, appointment.version,
				confirmations.findById(id).map(c -> VideoCompletionResponse.from(appointment, c,
						appointments.historicalCreditState(appointment.creditId).orElseThrow())).orElse(null));
	}

	@Transactional
	public PreparedRoom prepare(UUID id, UUID actor, String role) {
		var appointment = authorized(id, actor, role, true);
		requireJoin(appointment);
		var room = rooms.findById(id).orElseGet(() -> rooms.save(new VideoRoom(id, clock.instant())));
		if (!List.of("PREPARING", "OPEN").contains(room.state)) throw joinDenied();
		return new PreparedRoom(id, room.roomName, "PREPARING".equals(room.state));
	}

	@Transactional
	public VideoAccessResponse access(UUID id, UUID actor, String role) {
		var appointment = authorized(id, actor, role, true);
		requireJoin(appointment);
		var room = rooms.findById(id).orElseThrow(VideoSessionTransactions::joinDenied);
		if (!List.of("PREPARING", "OPEN").contains(room.state)) throw joinDenied();
		room.state = "OPEN";
		var now = clock.instant();
		var expiry = now.plus(properties.getTokenTtl());
		if (expiry.isAfter(appointment.endsAt)) expiry = appointment.endsAt;
		var identity = role.equals("USER") ? room.userIdentity : room.specialistIdentity;
		return new VideoAccessResponse(id, "LIVEKIT", properties.getUrl(), room.roomName,
				provider.issueToken(room.roomName, identity, now, expiry), expiry, appointment.endsAt);
	}

	@Transactional
	public void end(UUID id) {
		var appointment = appointments.lock(id).orElse(null);
		if (appointment == null || !"IN_APP_VIDEO".equals(appointment.modality)
				|| !List.of("CONFIRMED", "IN_PROGRESS").contains(appointment.status)
				|| clock.instant().isBefore(appointment.endsAt)) return;
		end(appointment, null, "SCHEDULED_WINDOW_ENDED", appointment.endsAt);
	}

	@Transactional(readOnly = true)
	public List<UUID> dueEndIds() { return appointments.dueEndIds(clock.instant()); }

	@Transactional(readOnly = true)
	public List<UUID> dueCloseIds() { return rooms.dueCloseIds(clock.instant()); }

	void end(VideoAppointment appointment, UUID actor, String reason, Instant actualEnd) {
		if ("SESSION_ENDED".equals(appointment.status)) return;
		var from = appointment.status;
		appointment.status = "SESSION_ENDED";
		appointment.policy = POLICY;
		appointment.endedAt = actualEnd;
		appointment.updatedAt = clock.instant();
		entities.persist(new VideoStatusHistory(appointment, from, actor, reason,
				"video-ended:" + appointment.id, appointment.updatedAt));
		entities.flush();
		outbox.record(appointment.id, UUID.randomUUID(), appointment.updatedAt);
	}

	@Transactional
	public Closure claimClosure(UUID id) {
		var appointment = appointments.lock(id).orElse(null);
		var room = rooms.findById(id).orElse(null);
		var now = clock.instant();
		if (appointment == null || room == null || List.of("CLOSED", "PURGED").contains(room.state)
				|| (room.nextCloseAt != null && now.isBefore(room.nextCloseAt))) return null;
		if (eligible(appointment) && now.isBefore(appointment.endsAt)) return null;
		room.state = "CLOSING";
		room.nextCloseAt = now.plusSeconds(15);
		return new Closure(id, room.roomName, List.of(room.userIdentity, room.specialistIdentity), now);
	}

	@Transactional
	public void closureResult(UUID id, boolean success) {
		appointments.lock(id).orElseThrow();
		var room = rooms.findById(id).orElseThrow();
		if (!"CLOSING".equals(room.state)) return;
		if (success) {
			room.state = "CLOSED";
			room.closedAt = clock.instant();
			room.nextCloseAt = null;
		}
		else room.failureReason = "PROVIDER_CLOSURE_UNCERTAIN";
	}

	@Transactional
	public void recloseAfterPreparation(UUID id) {
		appointments.lock(id).orElseThrow();
		var room = rooms.findById(id).orElseThrow();
		if ("PURGED".equals(room.state)) return;
		room.state = "CLOSING";
		room.closedAt = null;
		room.nextCloseAt = null;
	}

	VideoAppointment authorized(UUID id, UUID actor, String role, boolean lock) {
		if (!List.of("USER", "SPECIALIST").contains(role)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "VIDEO_ACCESS_FORBIDDEN", "Video requires an assigned participant");
		}
		var appointment = (lock ? appointments.lock(id) : appointments.findById(id)).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_VIDEO_NOT_FOUND", "Video appointment was not found"));
		if (!"IN_APP_VIDEO".equals(appointment.modality)
				|| !(role.equals("USER") ? appointment.userId : appointment.specialistId).equals(actor)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_VIDEO_NOT_FOUND", "Video appointment was not found");
		}
		return appointment;
	}

	boolean eligible(VideoAppointment appointment) {
		return List.of("CONFIRMED", "IN_PROGRESS").contains(appointment.status)
				&& appointments.wasConfirmed(appointment.id)
				&& appointments.specialistApproved(appointment.specialistId).orElse(false)
				&& appointments.creditState(appointment.creditId, appointment.id).orElse("").equals("HELD");
	}

	private void requireJoin(VideoAppointment appointment) {
		if (!properties.isEnabled() || !properties.configured()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VIDEO_DISABLED", "Video is currently unavailable");
		}
		var now = clock.instant();
		if (!eligible(appointment) || now.isBefore(appointment.startsAt) || !now.isBefore(appointment.endsAt)) throw joinDenied();
	}

	static ApiException joinDenied() {
		return new ApiException(HttpStatus.CONFLICT, "VIDEO_JOIN_NOT_ALLOWED", "The appointment does not currently permit video connection");
	}

	record PreparedRoom(UUID appointmentId, String roomName, boolean preparationRequired) { }
	record Closure(UUID appointmentId, String roomName, List<String> identities, Instant cutoff) { }
}
