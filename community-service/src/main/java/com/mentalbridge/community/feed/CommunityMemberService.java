package com.mentalbridge.community.feed;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityMemberService {
    private final CommunityMemberRepository members;
    private final CommunityChatProfiles profiles;

    public CommunityMemberService(CommunityMemberRepository members, CommunityChatProfiles profiles) {
        this.members = members; this.profiles = profiles;
    }

    @Transactional
    public Optional<CommunityResponses.Author> profile(UUID subject, UUID target) {
        var actor = profiles.ensure(subject);
        return visibleProfile(actor.id(), target);
    }

    @Transactional(readOnly = true)
    public Optional<CommunityResponses.Author> visibleProfile(UUID viewerProfileId, UUID target) {
        return members.visible(target, viewerProfileId).map(this::author);
    }

    @Transactional
    public MemberPage search(UUID subject, String query, UUID cursor, int limit) {
        var actor = profiles.ensure(subject);
        var page = members.search(actor.id(), query, cursor, PageRequest.of(0, limit + 1));
        return new MemberPage(page.stream().limit(limit).map(this::author).toList(), page.size() > limit ? page.get(limit - 1).id() : null);
    }

    private CommunityResponses.Author author(CommunityProfileEntity profile) {
        return new CommunityResponses.Author(profile.id(), profile.displayName(), profile.avatarPreset(),
                CommunityResponses.AuthorState.ACTIVE, profile.avatarImage());
    }

    public record MemberPage(List<CommunityResponses.Author> items, UUID nextCursor) {}
}
