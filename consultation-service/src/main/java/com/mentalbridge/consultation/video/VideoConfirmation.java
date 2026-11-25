package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "appointment_video_confirmation")
class VideoConfirmation {

	@Id @Column(name = "appointment_id") UUID appointmentId;
	UUID id;
	@Column(name = "confirmed_by") UUID confirmedBy;
	@Column(name = "source_version") long sourceVersion;
	@Column(name = "idempotency_key") String key;
	@Column(name = "accepted_at") Instant acceptedAt;
	@Column(name = "policy_version") String policy;

	protected VideoConfirmation() { }

	VideoConfirmation(VideoAppointment appointment, UUID actor, String key, Instant now) {
		appointmentId = appointment.id;
		id = UUID.randomUUID();
		confirmedBy = actor;
		sourceVersion = appointment.version;
		this.key = key;
		acceptedAt = now;
		policy = VideoSessionTransactions.POLICY;
	}
}
