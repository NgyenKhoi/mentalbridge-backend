package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "appointment_video_event")
class VideoEvent {

	@Id @Column(name = "event_id") String eventId;
	@Column(name = "appointment_id") UUID appointmentId;
	@Column(name = "event_type") String type;
	@Column(name = "room_sid") String roomSid;
	@Column(name = "participant_identity") String identity;
	@Column(name = "participant_sid") String participantSid;
	@Column(name = "participant_role") String role;
	@Column(name = "occurred_at") Instant occurredAt;
	@Column(name = "received_at") Instant receivedAt;

	protected VideoEvent() { }

	VideoEvent(UUID appointmentId, VideoProvider.VerifiedVideoEvent event, String role, Instant now) {
		this.appointmentId = appointmentId;
		eventId = event.id();
		type = event.type();
		roomSid = event.roomSid();
		identity = role == null ? null : event.identity();
		participantSid = role == null ? null : event.participantSid();
		this.role = role;
		occurredAt = event.occurredAt();
		receivedAt = now;
	}

	boolean matches(VideoEvent other) {
		return appointmentId.equals(other.appointmentId) && type.equals(other.type)
				&& roomSid.equals(other.roomSid) && java.util.Objects.equals(identity, other.identity)
				&& java.util.Objects.equals(participantSid, other.participantSid)
				&& java.util.Objects.equals(role, other.role) && occurredAt.equals(other.occurredAt);
	}
}
