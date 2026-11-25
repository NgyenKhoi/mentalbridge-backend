package com.mentalbridge.community.chat;

import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;

@Service
class ChatNotificationService {
    private final CommunityChatProfiles profiles;
    private final ChatConversationRepository conversations;
    private final ChatAccountAuthorizer accounts;
    private final ChatGroupAccess groups;
    private final ChatNotificationTransactions transactions;
    ChatNotificationService(CommunityChatProfiles profiles, ChatConversationRepository conversations,
            ChatAccountAuthorizer accounts, ChatGroupAccess groups, ChatNotificationTransactions transactions) {
        this.profiles = profiles; this.conversations = conversations; this.accounts = accounts;
        this.groups = groups; this.transactions = transactions;
    }
    void authorize(ChatNotificationController.Target target, UUID correlation) {
        var recipient = profiles.bySubject(target.recipientRoutingReference()).orElseThrow(ChatException::target);
        var actor = profiles.byId(target.actorCommunityProfileId()).orElseThrow(ChatException::target);
        if (recipient.id().equals(actor.id())) throw ChatException.target();
        var conversation = conversations.findById(target.conversationId()).orElseThrow(ChatException::target);
        if (conversation.kind.equals("GROUP")) {
            transactions.group(target, groups.authorize(recipient.subject(), conversation.id, actor.id(), correlation));
        } else {
            accounts.require(List.of(recipient.subject(), actor.subject()), correlation);
            transactions.direct(target, recipient.id());
        }
    }
}
