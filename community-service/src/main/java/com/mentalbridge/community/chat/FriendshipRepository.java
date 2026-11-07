package com.mentalbridge.community.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FriendshipRepository extends JpaRepository<FriendshipEntity, UUID> {
    Optional<FriendshipEntity> findByFirstProfileIdAndSecondProfileId(UUID first, UUID second);

    @Query("select f from FriendshipEntity f where (f.firstProfileId = :actor or f.secondProfileId = :actor) and f.id = :id")
    Optional<FriendshipEntity> owned(@Param("actor") UUID actor, @Param("id") UUID id);

    @Query("select case when f.firstProfileId = :actor then f.secondProfileId else f.firstProfileId end from FriendshipEntity f where (f.firstProfileId = :actor or f.secondProfileId = :actor) and f.id = :id")
    Optional<UUID> target(@Param("actor") UUID actor, @Param("id") UUID id);

    @Query(value = """
        select f.* from community_friendship f
        join community_profile peer on peer.id = case when f.first_profile_id = :actor then f.second_profile_id else f.first_profile_id end
        where (f.first_profile_id = :actor or f.second_profile_id = :actor)
          and peer.status = 'ACTIVE'
          and not exists (select 1 from community_access_restriction r where r.profile_id = peer.id and r.lifted_at is null)
          and not exists (select 1 from community_block b where (b.blocker_profile_id = :actor and b.blocked_profile_id = peer.id) or (b.blocker_profile_id = peer.id and b.blocked_profile_id = :actor))
          and ((:view = 'accepted' and f.state = 'ACCEPTED') or (:view = 'outgoing' and f.state = 'PENDING' and f.requester_profile_id = :actor) or (:view = 'incoming' and f.state = 'PENDING' and f.requester_profile_id <> :actor))
          and (cast(:cursor as uuid) is null or f.id > :cursor)
        order by f.id
        """, nativeQuery = true)
    List<FriendshipEntity> page(@Param("actor") UUID actor, @Param("view") String view, @Param("cursor") UUID cursor, Pageable pageable);
}
