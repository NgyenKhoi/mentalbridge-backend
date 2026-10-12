package com.mentalbridge.consultation.video;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface VideoAppointmentRepository extends JpaRepository<VideoAppointment, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from VideoAppointment a where a.id = :id")
	Optional<VideoAppointment> lock(UUID id);

	@Query(value = "select approval_status='APPROVED' from specialist_profile where account_id=:id", nativeQuery = true)
	Optional<Boolean> specialistApproved(UUID id);

	@Query(value = "select state from service_credit where id=:creditId and appointment_id=:appointmentId", nativeQuery = true)
	Optional<String> creditState(UUID creditId, UUID appointmentId);

	@Query(value = "select state from service_credit where id=:creditId", nativeQuery = true)
	Optional<String> historicalCreditState(UUID creditId);

	@Query(value = "select exists(select 1 from appointment_status_history where appointment_id=:id and to_status='CONFIRMED')", nativeQuery = true)
	boolean wasConfirmed(UUID id);

	@Query(value = "select exists(select 1 from appointment_dispute where appointment_id=:id)", nativeQuery = true)
	boolean hasDispute(UUID id);

	@Query(value = "select id from appointment where modality='IN_APP_VIDEO' and status in ('CONFIRMED','IN_PROGRESS') and scheduled_end_at<=:now order by scheduled_end_at,id limit 100", nativeQuery = true)
	List<UUID> dueEndIds(Instant now);
}
