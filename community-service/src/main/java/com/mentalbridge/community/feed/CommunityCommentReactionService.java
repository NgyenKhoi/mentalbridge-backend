package com.mentalbridge.community.feed;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.mentalbridge.community.shared.CommunityApiException;

@Service
class CommunityCommentReactionService {
    private final CommunityProfileRepository profiles;
    private final CommunityPostRepository posts;
    private final CommunityCommentRepository comments;
    private final CommunityCommentReactionRepository reactions;
    private final CommunityInteractionEventPolicy events;
    CommunityCommentReactionService(CommunityProfileRepository profiles, CommunityPostRepository posts,
            CommunityCommentRepository comments, CommunityCommentReactionRepository reactions, CommunityInteractionEventPolicy events) {
        this.profiles = profiles; this.posts = posts; this.comments = comments; this.reactions = reactions; this.events = events;
    }
    @Transactional
    View get(UUID subject, UUID comment) {
        var profile = profiles.findIdByAccountSubject(subject).orElse(null);
        target(comment, profile);
        return view(comment, profile);
    }
    @Transactional
    View put(UUID subject, UUID commentId, CommunityPostReactionEntity.Reaction reaction) {
        var now = Instant.now();
        profiles.createIfAbsent(UUID.randomUUID(), subject, "Thành viên", now);
        var profile = profiles.findByAccountSubjectForUpdate(subject).filter(p -> p.status() == CommunityProfileEntity.Status.ACTIVE)
                .orElseThrow(CommunityApiException::communityAccessUnavailable);
        var comment = target(commentId, profile.id());
        var id = new CommunityCommentReactionEntity.ReactionId(commentId, profile.id());
        var previous = reactions.findById(id);
        if (previous.isEmpty()) {
            reactions.saveAndFlush(new CommunityCommentReactionEntity(commentId, profile.id(), reaction, now));
            events.commentReactionCreated(comment, profile, reaction, now);
        } else if (previous.orElseThrow().reaction() != reaction) previous.orElseThrow().replace(reaction, now);
        return view(commentId, profile.id());
    }
    @Transactional
    void delete(UUID subject, UUID comment) {
        var profile = profiles.findIdByAccountSubject(subject).orElse(null);
        target(comment, profile);
        if (profile != null) reactions.findById(new CommunityCommentReactionEntity.ReactionId(comment, profile)).ifPresent(reactions::delete);
    }
    private CommunityCommentEntity target(UUID comment, UUID viewer) {
        var post = comments.reactionPost(comment).orElseThrow(CommunityApiException::commentNotFound);
        posts.findVisibleByIdForUpdate(post, viewer).orElseThrow(CommunityApiException::commentNotFound);
        return comments.reactionTarget(comment, viewer).orElseThrow(CommunityApiException::commentNotFound);
    }
    private View view(UUID comment, UUID profile) {
        var own = profile == null ? null : reactions.findById(new CommunityCommentReactionEntity.ReactionId(comment, profile))
                .map(CommunityCommentReactionEntity::reaction).orElse(null);
        return new View(comment, own, reactions.countByIdCommentId(comment));
    }
    record View(UUID commentId, CommunityPostReactionEntity.Reaction reaction, long reactionCount) {}
}
