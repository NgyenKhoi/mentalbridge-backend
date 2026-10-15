package com.mentalbridge.care.screeningepisode;

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

interface ScreeningEpisodeRepository extends JpaRepository<ScreeningEpisodeEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select episode from ScreeningEpisodeEntity episode
			where episode.userId = :userId and episode.purpose = :purpose
			  and episode.status in ('IN_PROGRESS','READY')
			order by episode.createdAt desc, episode.id desc
			""")
	List<ScreeningEpisodeEntity> findOpenForUpdate(@Param("userId") UUID userId,
			@Param("purpose") String purpose);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select episode from ScreeningEpisodeEntity episode
			where episode.id = :episodeId and episode.userId = :userId
			""")
	Optional<ScreeningEpisodeEntity> findOwnedForUpdate(@Param("userId") UUID userId,
			@Param("episodeId") UUID episodeId);

	Optional<ScreeningEpisodeEntity> findFirstByUserIdAndPurposeOrderByCreatedAtDescIdDesc(UUID userId,
			String purpose);

	Optional<ScreeningEpisodeEntity> findByUserIdAndSupportEvaluationId(UUID userId, UUID supportEvaluationId);

	@Query("""
			select episode from ScreeningEpisodeEntity episode
			where episode.userId = :userId
			  and episode.status in ('IN_PROGRESS','READY','COMPLETED')
			order by episode.createdAt desc, episode.id desc
			""")
	List<ScreeningEpisodeEntity> findHistory(
			@Param("userId") UUID userId,
			Pageable pageable);

	@Query("""
			select episode from ScreeningEpisodeEntity episode
			where episode.userId = :userId
			  and episode.status in ('IN_PROGRESS','READY','COMPLETED')
			  and (episode.createdAt < :cursorStartedAt
			       or (episode.createdAt = :cursorStartedAt and episode.id < :cursorId))
			order by episode.createdAt desc, episode.id desc
			""")
	List<ScreeningEpisodeEntity> findHistoryAfter(
			@Param("userId") UUID userId,
			@Param("cursorStartedAt") Instant cursorStartedAt,
			@Param("cursorId") UUID cursorId,
			Pageable pageable);

	@Query(value = """
			select submission.id as "assessmentId", submission.voided_at as "voidedAt",
			       definition.instrument as "instrument", result.total_score as "totalScore",
			       result.screening_level as "screeningLevel", result.safety_status as "safetyStatus"
			from assessment_submission submission
			join questionnaire_definition definition on definition.id = submission.definition_id
			left join assessment_result result on result.submission_id = submission.id
			where submission.user_id = :userId and submission.id in (:assessmentIds)
			""", nativeQuery = true)
	List<HistoryEvidenceRow> findHistoryEvidence(
			@Param("userId") UUID userId,
			@Param("assessmentIds") List<UUID> assessmentIds);

	interface HistoryEvidenceRow {
		UUID getAssessmentId();
		Instant getVoidedAt();
		String getInstrument();
		Integer getTotalScore();
		String getScreeningLevel();
		String getSafetyStatus();
	}
}
