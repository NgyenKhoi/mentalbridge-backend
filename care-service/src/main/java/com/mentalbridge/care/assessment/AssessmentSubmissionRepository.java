package com.mentalbridge.care.assessment;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

interface AssessmentSubmissionRepository extends JpaRepository<AssessmentSubmissionEntity, UUID> {

	Optional<AssessmentSubmissionEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

	Optional<AssessmentSubmissionEntity> findByAnonymousSessionIdAndIdempotencyKey(UUID anonymousSessionId,
			String idempotencyKey);

	Optional<AssessmentSubmissionEntity> findByIdAndUserId(UUID id, UUID userId);

	Optional<AssessmentSubmissionEntity> findByIdAndAnonymousSessionId(UUID id, UUID anonymousSessionId);

	List<AssessmentSubmissionEntity> findByUserIdAndVoidedAtIsNullOrderBySubmittedAtDescIdDesc(UUID userId,
			Pageable pageable);

	@Query("""
			select submission from AssessmentSubmissionEntity submission
			where submission.userId = :userId
			  and submission.voidedAt is null
			  and (submission.submittedAt < :cursorSubmittedAt
			       or (submission.submittedAt = :cursorSubmittedAt and submission.id < :cursorId))
			order by submission.submittedAt desc, submission.id desc
			""")
	List<AssessmentSubmissionEntity> findHistoryAfter(UUID userId, Instant cursorSubmittedAt, UUID cursorId,
			Pageable pageable);
}
