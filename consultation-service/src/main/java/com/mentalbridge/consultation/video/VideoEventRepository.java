package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface VideoEventRepository extends JpaRepository<VideoEvent, String> {

	List<VideoEvent> findByAppointmentId(UUID id);

	@Modifying
	@Query("delete from VideoEvent e where e.appointmentId=:id")
	void deleteForAppointment(UUID id);

	@Query(value = """
			select r.appointment_id from appointment_video_room r
			where r.state='CLOSED' and r.closed_at<:cutoff and r.retention_hold_reason is null
			and not exists(select 1 from appointment_dispute d where d.appointment_id=r.appointment_id)
			and not exists(select 1 from appointment_video_event e where e.appointment_id=r.appointment_id and e.received_at>=:cutoff)
			order by r.closed_at,r.appointment_id limit 100
			""", nativeQuery = true)
	List<UUID> expiredRoomIds(Instant cutoff);
}
