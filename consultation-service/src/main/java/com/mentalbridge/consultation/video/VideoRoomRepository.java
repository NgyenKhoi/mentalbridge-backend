package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface VideoRoomRepository extends JpaRepository<VideoRoom, UUID> {

	Optional<VideoRoom> findByRoomName(String roomName);

	@Query("select r.appointmentId from VideoRoom r where r.roomName=:roomName")
	Optional<UUID> findAppointmentIdByRoomName(String roomName);

	@Query(value = """
			select r.appointment_id from appointment_video_room r join appointment a on a.id=r.appointment_id
			join specialist_profile p on p.account_id=a.specialist_account_id
			where r.state in ('PREPARING','OPEN','CLOSING')
			and (a.scheduled_end_at<=:now or a.status not in ('CONFIRMED','IN_PROGRESS') or p.approval_status<>'APPROVED')
			and (r.next_close_at is null or r.next_close_at<=:now)
			order by a.scheduled_end_at,r.appointment_id limit 100
			""", nativeQuery = true)
	List<UUID> dueCloseIds(Instant now);
}
