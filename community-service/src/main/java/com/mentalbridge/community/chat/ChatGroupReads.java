package com.mentalbridge.community.chat;

import java.util.*;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatGroupReads {
    private final ChatGroupAccess access;
    private final ChatGroupTransactions transactions;
    private final ChatInvitationRepository invitations;
    private final ChatCursors cursors;
    private final ChatPrivacyRepository privacy;
    private final Clock clock;
    ChatGroupReads(ChatGroupAccess access, ChatGroupTransactions transactions, ChatInvitationRepository invitations,
            ChatCursors cursors, ChatPrivacyRepository privacy, Clock clock) {
        this.access = access; this.transactions = transactions; this.invitations = invitations; this.cursors = cursors; this.privacy = privacy; this.clock = clock;
    }
    @Transactional
    ChatModels.Page<ChatGroupModels.Member> members(UUID conversation, ChatGroupAccess.Authorization auth, String cursor, int limit) {
        var context = access.lock(conversation, auth, true);
        var scope = "members:" + conversation + ":" + context.member().id + ":" + context.group().managementVersion;
        var epoch = privacy.findById(auth.actor()).map(p -> p.visibilityEpoch).orElse(0L);
        var before = cursors.decode(auth.actor(), scope, epoch, cursor);
        var eligible = context.members().stream().sorted(Comparator.comparing((ChatMembershipEntity m) -> m.joinedAt)
                .thenComparing(m -> m.id.toString()).reversed()).filter(m -> m.joinedAt.isBefore(before.time())
                        || (m.joinedAt.equals(before.time()) && m.id.toString().compareTo(before.id().toString()) < 0)).toList();
        var page = eligible.stream().limit(limit).toList();
        var result = page.stream().map(m -> new ChatGroupModels.Member(access.peer(context, m.profileId), m.role, m.joinedAt)).toList();
        var last = page.isEmpty() ? null : page.getLast();
        return new ChatModels.Page<>(result, eligible.size() > limit ? cursors.encode(auth.actor(), scope, epoch, last.joinedAt, last.id) : null);
    }
    @Transactional
    Optional<ChatGroupModels.Invitation> invitation(UUID id, UUID conversation, ChatGroupAccess.Authorization auth) {
        var context = access.lock(conversation, auth, false);
        return invitations.findById(id).filter(i -> i.inviteeProfileId.equals(auth.actor()) && transactions.valid(i, context))
                .map(i -> transactions.invitation(i, context));
    }
}
