package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "appointment_status_history")
class VideoStatusHistory {

	@Id UUID id;
	@Column(name = "appointment_id") UUID appointmentId;
	@Column(name = "from_status") String fromStatus;
	@Column(name = "to_status") String toStatus;
	@Column(name = "changed_by") UUID changedBy;
	String reason;
	@Column(name = "idempotency_key") String key;
	@Column(name = "changed_at") Instant changedAt;

	protected VideoStatusHistory() { }

	VideoStatusHistory(VideoAppointment appointment, String from, UUID actor, String reason, String key, Instant now) {
		id = UUID.randomUUID();
		appointmentId = appointment.id;
		fromStatus = from;
		toStatus = appointment.status;
		changedBy = actor;
		this.reason = reason;
		this.key = key;
		changedAt = now;
	}
}
