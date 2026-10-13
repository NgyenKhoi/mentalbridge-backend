package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "appointment")
@DynamicUpdate
class VideoAppointment {

	@Id UUID id;
	@Column(name = "user_account_id") UUID userId;
	@Column(name = "specialist_account_id") UUID specialistId;
	@Column(name = "service_credit_id") UUID creditId;
	String status;
	String modality;
	@Column(name = "scheduled_start_at") Instant startsAt;
	@Column(name = "scheduled_end_at") Instant endsAt;
	@Column(name = "session_policy_version") String policy;
	@Column(name = "session_ended_at") Instant endedAt;
	@Column(name = "session_settled_at") Instant settledAt;
	@Column(name = "session_outcome") String outcome;
	@Column(name = "session_outcome_reason") String outcomeReason;
	@Column(name = "completion_fact_id") UUID completionFactId;
	@Column(name = "video_confirmation_id") UUID videoConfirmationId;
	@Column(name = "evidence_review_started_at") Instant reviewStartedAt;
	@Column(name = "updated_at") Instant updatedAt;
	@Version long version;

	protected VideoAppointment() { }
}
