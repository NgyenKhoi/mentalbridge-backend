package com.mentalbridge.care.assessment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AssessmentSubmissionRepository extends JpaRepository<AssessmentSubmissionEntity, UUID> {

	Optional<AssessmentSubmissionEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

	Optional<AssessmentSubmissionEntity> findByAnonymousSessionIdAndIdempotencyKey(UUID anonymousSessionId,
			String idempotencyKey);

	Optional<AssessmentSubmissionEntity> findByIdAndUserId(UUID id, UUID userId);

	Optional<AssessmentSubmissionEntity> findByIdAndAnonymousSessionId(UUID id, UUID anonymousSessionId);
}
