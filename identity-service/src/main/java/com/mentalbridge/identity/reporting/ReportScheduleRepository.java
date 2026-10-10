package com.mentalbridge.identity.reporting;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface ReportScheduleRepository extends JpaRepository<ReportScheduleEntity, UUID> {
	long countByStatusNot(String status);
	List<ReportScheduleEntity> findByStatusNotOrderByCreatedAtDescIdDesc(String status, Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select schedule from ReportScheduleEntity schedule where schedule.id = :id")
	Optional<ReportScheduleEntity> lockById(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select schedule from ReportScheduleEntity schedule where schedule.status = 'ACTIVE' and schedule.nextRunAt <= :now order by schedule.nextRunAt, schedule.id")
	List<ReportScheduleEntity> due(@Param("now") Instant now, Pageable pageable);
}
