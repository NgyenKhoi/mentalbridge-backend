package com.mentalbridge.care.screeningepisode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ScreeningEpisodeRepository extends JpaRepository<ScreeningEpisodeEntity, UUID> {

	long countByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(String status, Instant from, Instant to);

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
}
