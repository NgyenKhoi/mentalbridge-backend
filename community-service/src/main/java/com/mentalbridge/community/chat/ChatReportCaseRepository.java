package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChatReportCaseRepository extends JpaRepository<ChatReportCaseEntity, UUID> {
    Optional<ChatReportCaseEntity> findByReporterProfileIdAndMessageId(UUID reporter, UUID message);
    long countByReporterProfileIdAndCreatedAtAfter(UUID reporter, Instant after);
}
