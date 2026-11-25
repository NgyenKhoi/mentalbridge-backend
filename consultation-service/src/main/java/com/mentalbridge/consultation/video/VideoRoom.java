package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "appointment_video_room")
class VideoRoom {

	@Id @Column(name = "appointment_id") UUID appointmentId;
	@Column(name = "room_name") String roomName;
	@Column(name = "user_identity") String userIdentity;
	@Column(name = "specialist_identity") String specialistIdentity;
	String state;
	@Column(name = "failure_reason") String failureReason;
	@Column(name = "next_close_at") Instant nextCloseAt;
	@Column(name = "next_verification_at") Instant nextVerificationAt;
	@Column(name = "closed_at") Instant closedAt;
	@Column(name = "created_at") Instant createdAt;
	@Column(name = "retention_hold_reason") String retentionHoldReason;
	@Version long version;

	protected VideoRoom() { }

	VideoRoom(UUID appointmentId, Instant now) {
		this.appointmentId = appointmentId;
		roomName = "mb-" + UUID.randomUUID();
		userIdentity = "p-" + UUID.randomUUID();
		specialistIdentity = "p-" + UUID.randomUUID();
		state = "PREPARING";
		createdAt = now;
	}
}
