package com.mentalbridge.identity.reporting;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.LockModeType;

interface PlatformReportJobRepository extends JpaRepository<PlatformReportJobEntity, UUID> {

	Optional<PlatformReportJobEntity> findByRequestedByAndIdempotencyKey(UUID requestedBy, String idempotencyKey);

	@Query("""
			select job from PlatformReportJobEntity job
			order by job.requestedAt desc, job.id desc
			""")
	List<PlatformReportJobEntity> browseFirstPage(Pageable pageable);

	@Query("""
			select job from PlatformReportJobEntity job
			where job.requestedAt < :afterAt
			   or (job.requestedAt = :afterAt and job.id < :afterId)
			order by job.requestedAt desc, job.id desc
			""")
	List<PlatformReportJobEntity> browseAfter(@Param("afterAt") Instant afterAt, @Param("afterId") UUID afterId,
			Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<PlatformReportJobEntity> findFirstByStatusOrderByRequestedAtAscIdAsc(PlatformReportStatus status);

}
