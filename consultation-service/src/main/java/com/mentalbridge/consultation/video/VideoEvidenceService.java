package com.mentalbridge.consultation.video;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

@Service
class VideoEvidenceService {

	private final VideoAppointmentRepository appointments;
	private final VideoRoomRepository rooms;
	private final VideoEventRepository events;
	private final Clock clock;

	VideoEvidenceService(VideoAppointmentRepository appointments, VideoRoomRepository rooms,
			VideoEventRepository events, Clock clock) {
		this.appointments = appointments;
		this.rooms = rooms;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public void record(VideoProvider.VerifiedVideoEvent event) {
		if (!List.of("participant_joined", "participant_left", "room_finished").contains(event.type())) return;
		if (!bounded(event.id(), 128) || !bounded(event.roomSid(), 128) || event.occurredAt() == null
				|| event.occurredAt().isAfter(clock.instant().plusSeconds(30))) throw invalid();
		var found = rooms.findAppointmentIdByRoomName(event.room());
		if (found.isEmpty()) return;
		var appointment = appointments.lock(found.get()).orElseThrow();
		var room = rooms.findById(appointment.id).orElseThrow();
		if ("PURGED".equals(room.state)) return;
		String role = null;
		if (!"room_finished".equals(event.type())) {
			if (!bounded(event.participantSid(), 128)) throw invalid();
			if (room.userIdentity.equals(event.identity())) role = "USER";
			else if (room.specialistIdentity.equals(event.identity())) role = "SPECIALIST";
			else { room.failureReason = "UNEXPECTED_PARTICIPANT"; return; }
		}
		var candidate = new VideoEvent(appointment.id, event, role, clock.instant());
		var existing = events.findById(event.id());
		if (existing.isPresent()) {
			if (!existing.get().matches(candidate)) room.failureReason = "CONFLICTING_PROVIDER_EVENT";
			return;
		}
		if (event.occurredAt().isBefore(appointment.startsAt)
				|| ("participant_joined".equals(event.type()) && !event.occurredAt().isBefore(appointment.endsAt))) {
			room.failureReason = "OUT_OF_WINDOW_PARTICIPATION";
		}
		events.save(candidate);
		room.nextVerificationAt = clock.instant();
	}

	@Transactional(readOnly = true)
	public List<UUID> expiredRooms() { return events.expiredRoomIds(clock.instant().minus(Duration.ofDays(90))); }

	@Transactional
	public void purge(UUID id) {
		var appointment = appointments.lock(id).orElseThrow();
		var room = rooms.findById(id).orElseThrow();
		var cutoff = clock.instant().minus(Duration.ofDays(90));
		if (!"CLOSED".equals(room.state) || !room.closedAt.isBefore(cutoff)
				|| room.retentionHoldReason != null || appointments.hasDispute(id)
				|| events.findByAppointmentId(id).stream().anyMatch(e -> !e.receivedAt.isBefore(cutoff))
				|| !List.of("SESSION_ENDED", "COMPLETED", "CANCELLED", "REJECTED", "EXPIRED").contains(appointment.status)) return;
		events.deleteForAppointment(id);
		room.roomName = "purged-" + UUID.randomUUID();
		room.userIdentity = "purged-" + UUID.randomUUID();
		room.specialistIdentity = "purged-" + UUID.randomUUID();
		room.failureReason = null;
		room.state = "PURGED";
	}

	private boolean bounded(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }

	private ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "VIDEO_EVENT_INVALID", "Video event metadata is invalid");
	}
}
