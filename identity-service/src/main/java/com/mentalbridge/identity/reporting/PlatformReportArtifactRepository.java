package com.mentalbridge.identity.reporting;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PlatformReportArtifactRepository extends JpaRepository<PlatformReportArtifactEntity, UUID> {

	long countByRetainedUntilBefore(Instant cutoff);

	long deleteByRetainedUntilBefore(Instant cutoff);

}
