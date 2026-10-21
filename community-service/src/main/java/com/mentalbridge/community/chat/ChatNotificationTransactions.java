package com.mentalbridge.community.chat;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatNotificationTransactions {
    private final ChatAccess access;
    private final ChatGroupAccess groups;
    private final ChatGroupTransactions invitations;
    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatRequestRepository requests;
    private final ChatInvitationRepository invites;
    private final Clock clock;
    ChatNotificationTransactions(ChatAccess access, ChatGroupAccess groups, ChatGroupTransactions invitations,
            ChatConversationRepository conversations, ChatMessageRepository messages, ChatRequestRepository requests,
            ChatInvitationRepository invites, Clock clock) {
        this.access = access; this.groups = groups; this.invitations = invitations; this.conversations = conversations;
        this.messages = messages; this.requests = requests; this.invites = invites; this.clock = clock;
    }
    @Transactional
    void direct(ChatNotificationController.Target target, UUID recipient) {
        access.pair(recipient, target.actorCommunityProfileId());
        var conversation = conversations.lock(target.conversationId()).orElseThrow(ChatException::target);
        if (!conversation.other(recipient).equals(target.actorCommunityProfileId())) throw ChatException.target();
        switch (target.kind()) {
            case MESSAGE_RECEIVED -> {
                if (!conversation.state.equals("ACTIVE")) throw ChatException.target();
                message(target);
            }
            case DIRECT_REQUESTED -> {
                var request = requests.findById(target.targetId()).orElseThrow(ChatException::target);
                if (!request.conversationId.equals(conversation.id) || !request.recipientProfileId.equals(recipient)
                        || !request.requesterProfileId.equals(target.actorCommunityProfileId()) || !request.pending(clock.instant()))
                    throw ChatException.target();
            }
            default -> throw ChatException.target();
        }
    }
    @Transactional
    void group(ChatNotificationController.Target target, ChatGroupAccess.Authorization authorization) {
        var context = groups.lock(target.conversationId(), authorization, target.kind() != ChatNotificationController.Kind.GROUP_INVITED);
        if (!groups.visible(context, target.actorCommunityProfileId())) throw ChatException.target();
        switch (target.kind()) {
            case MESSAGE_RECEIVED -> {
                var message = message(target);
                if (!groups.messageVisible(context, message)) throw ChatException.target();
            }
            case GROUP_INVITED -> {
                var invite = invites.findById(target.targetId()).orElseThrow(ChatException::target);
                if (!invite.conversationId.equals(context.group().id) || !invite.inviteeProfileId.equals(context.actor().id())
                        || !invite.inviterProfileId.equals(target.actorCommunityProfileId()) || !invitations.valid(invite, context))
                    throw ChatException.target();
            }
            default -> throw ChatException.target();
        }
    }
    private ChatMessageEntity message(ChatNotificationController.Target target) {
        return messages.findById(target.targetId()).filter(message -> message.conversationId.equals(target.conversationId())
                && message.senderProfileId.equals(target.actorCommunityProfileId()) && message.readable(clock.instant()))
                .orElseThrow(ChatException::target);
    }
}
