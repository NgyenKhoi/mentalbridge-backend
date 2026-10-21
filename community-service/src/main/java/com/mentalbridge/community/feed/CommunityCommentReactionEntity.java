package com.mentalbridge.community.feed;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "community_comment_reaction")
class CommunityCommentReactionEntity {
    @EmbeddedId private ReactionId id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private CommunityPostReactionEntity.Reaction reaction;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected CommunityCommentReactionEntity() {}
    CommunityCommentReactionEntity(UUID comment, UUID profile, CommunityPostReactionEntity.Reaction reaction, Instant now) {
        id = new ReactionId(comment, profile); this.reaction = reaction; createdAt = now; updatedAt = now;
    }
    CommunityPostReactionEntity.Reaction reaction() { return reaction; }
    void replace(CommunityPostReactionEntity.Reaction value, Instant now) { reaction = value; updatedAt = now; }
    @Embeddable
    static class ReactionId implements Serializable {
        @Column(name = "comment_id") private UUID commentId;
        @Column(name = "profile_id") private UUID profileId;
        protected ReactionId() {}
        ReactionId(UUID comment, UUID profile) { commentId = comment; profileId = profile; }
        @Override public boolean equals(Object other) { return this == other || other instanceof ReactionId value && Objects.equals(commentId, value.commentId) && Objects.equals(profileId, value.profileId); }
        @Override public int hashCode() { return Objects.hash(commentId, profileId); }
    }
}
