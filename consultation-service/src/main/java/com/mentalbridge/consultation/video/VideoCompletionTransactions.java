package com.mentalbridge.consultation.video;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.appointment.AppointmentStatusOutbox;
import com.mentalbridge.consultation.credits.CreditEventType;
import com.mentalbridge.consultation.credits.ServiceCreditService;
import com.mentalbridge.consultation.earnings.SpecialistEarningService;
import com.mentalbridge.consultation.shared.ApiException;

@Service
class VideoCompletionTransactions {

	private final VideoSessionTransactions sessions;
	private final VideoAppointmentRepository appointments;
	private final VideoRoomRepository rooms;
	private final VideoEventRepository events;
	private final VideoConfirmationRepository confirmations;
	private final ServiceCreditService credits;
	private final SpecialistEarningService earnings;
	private final AppointmentStatusOutbox outbox;
	private final EntityManager entities;
	private final Clock clock;

	VideoCompletionTransactions(VideoSessionTransactions sessions, VideoAppointmentRepository appointments,
			VideoRoomRepository rooms, VideoEventRepository events, VideoConfirmationRepository confirmations,
			ServiceCreditService credits, SpecialistEarningService earnings, AppointmentStatusOutbox outbox,
			EntityManager entities, Clock clock) {
		this.sessions = sessions;
		this.appointments = appointments;
		this.rooms = rooms;
		this.events = events;
		this.confirmations = confirmations;
		this.credits = credits;
		this.earnings = earnings;
		this.outbox = outbox;
		this.entities = entities;
		this.clock = clock;
	}

	@Transactional
	public boolean confirm(UUID id, UUID actor, String role, long expectedVersion, String key) {
		if (!"SPECIALIST".equals(role)) throw forbidden();
		var appointment = sessions.authorized(id, actor, role, true);
		if (!appointments.specialistApproved(actor).orElse(false)) throw forbidden();
		var keyMatch = confirmations.findByConfirmedByAndKey(actor, key);
		if (keyMatch.isPresent() && !id.equals(keyMatch.get().appointmentId)) throw conflict();
		var existing = confirmations.findById(id);
		if (existing.isPresent()) {
			if (!existing.get().confirmedBy.equals(actor) || !existing.get().key.equals(key)) throw conflict();
			return true;
		}
		if (appointment.version != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "APPOINTMENT_VERSION_CONFLICT", "Appointment version changed");
		}
		var now = clock.instant();
		if (!List.of("CONFIRMED", "IN_PROGRESS", "SESSION_ENDED").contains(appointment.status)
				|| now.isBefore(appointment.startsAt) || !appointments.wasConfirmed(id)
				|| appointments.hasDispute(id) || rooms.findById(id).isEmpty()
				|| !appointments.creditState(appointment.creditId, id).orElse("").equals("HELD")
				|| ("SESSION_ENDED".equals(appointment.status) && !VideoSessionTransactions.POLICY.equals(appointment.policy))) throw conflict();
		var room = rooms.findById(id).orElseThrow();
		if ("PURGED".equals(room.state)) throw conflict();
		var confirmation = new VideoConfirmation(appointment, actor, key, now);
		confirmations.saveAndFlush(confirmation);
		appointment.videoConfirmationId = confirmation.id;
		if (!"SESSION_ENDED".equals(appointment.status)) {
			sessions.end(appointment, actor, "SPECIALIST_VIDEO_END_CONFIRMED", now.isAfter(appointment.endsAt) ? appointment.endsAt : now);
		}
		else {
			appointment.updatedAt = now;
			entities.persist(new VideoStatusHistory(appointment, appointment.status, actor, "SPECIALIST_VIDEO_END_CONFIRMED",
					"video-confirmed:" + id, now));
			entities.flush();
			outbox.record(id, UUID.randomUUID(), now);
		}
		return false;
	}

	@Transactional
	public void reconcile(UUID id) {
		var appointment = appointments.lock(id).orElseThrow();
		if (!"IN_APP_VIDEO".equals(appointment.modality) || !"SESSION_ENDED".equals(appointment.status)
				|| !VideoSessionTransactions.POLICY.equals(appointment.policy) || appointment.completionFactId != null) return;
		var now = clock.instant();
		var confirmation = confirmations.findById(id).orElse(null);
		var room = rooms.findById(id).orElse(null);
		if (room != null) room.nextVerificationAt = now.plusSeconds(30);
		var cutoff = appointment.endedAt;
		var evidence = events.findByAppointmentId(id);
		var attendance = VideoAttendance.evaluate(evidence, appointment.startsAt, cutoff);
		var consistent = evidence.stream().allMatch(e -> !e.occurredAt.isBefore(appointment.startsAt)
				&& !e.occurredAt.isAfter(cutoff.plusSeconds(300))
				&& (!"participant_joined".equals(e.type) || e.occurredAt.isBefore(cutoff)));
		if (confirmation == null || room == null || !"CLOSED".equals(room.state) || room.failureReason != null
				|| !attendance.completePairs() || attendance.overlapSeconds() < 60 || !consistent
				|| appointments.hasDispute(id) || !appointments.specialistApproved(appointment.specialistId).orElse(false)
				|| !appointments.creditState(appointment.creditId, id).orElse("").equals("HELD")) {
			if (appointment.reviewStartedAt == null && !now.isBefore((confirmation == null ? cutoff : confirmation.acceptedAt).plusSeconds(86400))) {
				appointment.reviewStartedAt = now;
				appointment.updatedAt = now;
				entities.persist(new VideoStatusHistory(appointment, appointment.status, null, "VIDEO_MANUAL_REVIEW_REQUIRED",
						"video-manual-review:" + id, now));
				entities.flush();
				outbox.record(id, UUID.randomUUID(), now);
			}
			return;
		}
		credits.transition(appointment.userId, appointment.creditId, id, CreditEventType.CONSUMED, "session-settle:" + id);
		appointment.status = "COMPLETED";
		appointment.outcome = "COMPLETED";
		appointment.outcomeReason = "EVIDENCE_REQUIREMENTS_MET";
		appointment.completionFactId = UUID.randomUUID();
		appointment.settledAt = now;
		appointment.updatedAt = now;
		entities.persist(new VideoStatusHistory(appointment, "SESSION_ENDED", null,
				"EVIDENCE_REQUIREMENTS_MET", "session-completed:" + id, now));
		entities.flush();
		earnings.createForCompletedAppointment(id);
		outbox.record(id, UUID.randomUUID(), now);
	}

	@Transactional(readOnly = true)
	public List<UUID> pendingIds() { return confirmations.pendingIds(clock.instant()); }

	@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public VideoCompletionResponse response(UUID id, UUID actor, String role) {
		var appointment = sessions.authorized(id, actor, role, false);
		return VideoCompletionResponse.from(appointment, confirmations.findById(id).orElseThrow(),
				appointments.historicalCreditState(appointment.creditId).orElseThrow());
	}

	private ApiException forbidden() {
		return new ApiException(HttpStatus.FORBIDDEN, "VIDEO_COMPLETION_FORBIDDEN", "Video completion requires the assigned approved specialist");
	}

	private ApiException conflict() {
		return new ApiException(HttpStatus.CONFLICT, "VIDEO_COMPLETION_CONFLICT", "Video completion is not currently permitted");
	}
}
