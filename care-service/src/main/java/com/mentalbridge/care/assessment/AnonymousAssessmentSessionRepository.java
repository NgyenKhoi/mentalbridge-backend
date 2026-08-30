package com.mentalbridge.care.assessment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface AnonymousAssessmentSessionRepository extends JpaRepository<AnonymousAssessmentSessionEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select session from AnonymousAssessmentSessionEntity session where session.id = :sessionId")
	Optional<AnonymousAssessmentSessionEntity> findByIdForUpdate(@Param("sessionId") UUID sessionId);
}
