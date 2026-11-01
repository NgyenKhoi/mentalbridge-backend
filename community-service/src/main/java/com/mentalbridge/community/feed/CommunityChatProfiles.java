package com.mentalbridge.community.feed;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityChatProfiles {
    private final CommunityProfileRepository profiles;
    private final Clock clock;
    private final jakarta.persistence.EntityManager entityManager;

    public CommunityChatProfiles(CommunityProfileRepository profiles, Clock clock, jakarta.persistence.EntityManager entityManager) {
        this.profiles = profiles;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Optional<Profile> byId(UUID id) {
        return profiles.findById(id).map(this::view);
    }

    @Transactional(readOnly = true)
    public Optional<Profile> bySubject(UUID subject) {
        return profiles.findByAccountSubject(subject).map(this::view);
    }
    @Transactional(readOnly = true)
    public List<Profile> byIds(Collection<UUID> ids) { return profiles.findAllById(ids).stream().map(this::view).toList(); }

    @Transactional(propagation = Propagation.MANDATORY)
    public Profile ensure(UUID subject) {
        profiles.createIfAbsent(UUID.randomUUID(), subject, "Thành viên MentalBridge", clock.instant());
        return view(profiles.findByAccountSubject(subject).orElseThrow());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Profile> lock(Collection<UUID> ids) {
        return profiles.lockChatProfiles(ids).stream().map(profile -> {
            entityManager.refresh(profile);
            return view(profile);
        }).toList();
    }

    private Profile view(CommunityProfileEntity profile) {
        return new Profile(profile.id(), profile.accountSubject(), profile.displayName(),
                profile.status() == CommunityProfileEntity.Status.ACTIVE);
    }

    public record Profile(UUID id, UUID subject, String displayName, boolean active) {
        @Override public String toString() { return "CommunityChatProfile[REDACTED]"; }
    }
}
