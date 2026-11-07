package com.mentalbridge.community.feed;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommunityMemberRepository extends JpaRepository<CommunityProfileEntity, UUID> {
    String VISIBLE = """
        select p.* from community_profile p where p.status = 'ACTIVE'
          and not exists (select 1 from community_access_restriction r where r.profile_id in (p.id, :viewer) and r.lifted_at is null)
          and not exists (select 1 from community_block b where (b.blocker_profile_id = :viewer and b.blocked_profile_id = p.id) or (b.blocker_profile_id = p.id and b.blocked_profile_id = :viewer))
        """;

    @Query(value = VISIBLE + " and p.id = :id", nativeQuery = true)
    Optional<CommunityProfileEntity> visible(@Param("id") UUID id, @Param("viewer") UUID viewer);

    @Query(value = VISIBLE + " and p.id <> :viewer and position(lower(:query) in lower(p.display_name)) > 0 and (cast(:cursor as uuid) is null or p.id > :cursor) order by p.id", nativeQuery = true)
    List<CommunityProfileEntity> search(@Param("viewer") UUID viewer, @Param("query") String query, @Param("cursor") UUID cursor, Pageable page);
}
