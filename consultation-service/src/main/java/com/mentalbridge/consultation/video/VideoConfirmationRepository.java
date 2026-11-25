package com.mentalbridge.consultation.video;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface VideoConfirmationRepository extends JpaRepository<VideoConfirmation, UUID> {

	Optional<VideoConfirmation> findByConfirmedByAndKey(UUID actor, String key);

	@Query(value = """
			select a.id from appointment a left join appointment_video_room r on r.appointment_id=a.id
			where a.modality='IN_APP_VIDEO' and a.status='SESSION_ENDED' and a.session_outcome is null
			and ((r.state<>'PURGED' and (r.next_verification_at is null or r.next_verification_at<=:now))
			  or (r.appointment_id is null and a.evidence_review_started_at is null and a.session_ended_at+interval '24 hours'<=:now))
			order by r.next_verification_at nulls first,a.id limit 100
			""", nativeQuery = true)
	List<UUID> pendingIds(Instant now);
}
