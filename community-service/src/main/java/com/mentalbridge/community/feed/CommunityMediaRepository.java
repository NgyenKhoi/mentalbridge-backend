package com.mentalbridge.community.feed;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityMediaRepository extends JpaRepository<CommunityMediaEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select media from CommunityMediaEntity media where media.id in :ids")
	List<CommunityMediaEntity> findAllByIdForUpdate(@Param("ids") List<UUID> ids);

	@Query("select media from CommunityMediaEntity media join fetch media.owner owner where media.id = :id and owner.accountSubject = :subject")
	Optional<CommunityMediaEntity> findOwnedById(@Param("id") UUID id, @Param("subject") UUID subject);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select media from CommunityMediaEntity media join fetch media.owner owner where media.id = :id and owner.accountSubject = :subject")
	Optional<CommunityMediaEntity> findOwnedByIdForUpdate(@Param("id") UUID id, @Param("subject") UUID subject);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select media from CommunityMediaEntity media where media.id = :id")
	Optional<CommunityMediaEntity> findByIdForUpdate(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select media from CommunityMediaEntity media where media.owner.id = :ownerId and media.idempotencyKey = :key")
	Optional<CommunityMediaEntity> findByOwnerAndIdempotencyKeyForUpdate(@Param("ownerId") UUID ownerId,
			@Param("key") String key);

	@Query("select count(media) from CommunityMediaEntity media where media.owner.id = :ownerId and media.post is null and media.state in :states")
	long countUnattachedByOwnerAndStateIn(@Param("ownerId") UUID ownerId,
			@Param("states") List<CommunityMediaEntity.State> states);

	@Query(value = """
			select * from community_media
			where post_id is null
			  and storage_key is not null
			  and state in ('PENDING', 'PROCESSING', 'READY', 'REJECTED', 'DELETED', 'EXPIRED')
			  and ((state = 'PENDING' and upload_expires_at < :now)
			       or (state in ('PROCESSING', 'READY', 'REJECTED') and updated_at < :retainedBefore)
			       or state in ('DELETED', 'EXPIRED'))
			order by updated_at, id
			limit :limit
			for update skip locked
			""", nativeQuery = true)
	List<CommunityMediaEntity> findCleanupBatch(@Param("now") java.time.Instant now,
			@Param("retainedBefore") java.time.Instant retainedBefore, @Param("limit") int limit);
}
