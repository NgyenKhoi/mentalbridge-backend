package com.mentalbridge.community.chat;

import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

interface ChatPrivacyRepository extends JpaRepository<ChatPrivacyEntity, UUID> {
    @Query(value = """
            select exists (select 1 from community_access_restriction where profile_id in (:profiles) and lifted_at is null)
            """, nativeQuery = true)
    boolean restricted(@Param("profiles") List<UUID> profiles);
    @Query(value = """
            select exists (select 1 from community_block where (blocker_profile_id = :first and blocked_profile_id = :second)
            or (blocker_profile_id = :second and blocked_profile_id = :first))
            """, nativeQuery = true)
    boolean blocked(@Param("first") UUID first, @Param("second") UUID second);
    @Modifying
    @Query(value = """
            insert into community_block (blocker_profile_id, blocked_profile_id, created_at)
            values (:first, :second, :now) on conflict do nothing
            """, nativeQuery = true)
    int block(@Param("first") UUID first, @Param("second") UUID second, @Param("now") java.time.Instant now);
    @Modifying
    @Query(value = "delete from community_block where blocker_profile_id = :first and blocked_profile_id = :second", nativeQuery = true)
    int unblock(@Param("first") UUID first, @Param("second") UUID second);
}
