package com.mentalbridge.community.chat;

import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;

@Service
class CommunityChatPresenceService {
    private final ChatConversationRepository conversations;
    private final CommunityChatProfiles profiles;
    private final ChatAccountAuthorizer accounts;
    private final ChatGroupAccess groups;
    private final ChatPresenceTransactions transactions;

    CommunityChatPresenceService(ChatConversationRepository conversations, CommunityChatProfiles profiles,
            ChatAccountAuthorizer accounts, ChatGroupAccess groups, ChatPresenceTransactions transactions) {
        this.conversations = conversations; this.profiles = profiles; this.accounts = accounts;
        this.groups = groups; this.transactions = transactions;
    }

    ChatPresenceTransactions.Projection projection(UUID subject, UUID conversation, UUID correlation) {
        var current = conversations.findById(conversation).orElseThrow(ChatException::target);
        if (current.kind.equals("GROUP")) {
            return transactions.group(conversation, groups.authorize(subject, conversation, null, correlation));
        }
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var peer = profiles.byId(current.other(actor.id())).orElseThrow(ChatException::target);
        accounts.require(List.of(subject, peer.subject()), correlation);
        return transactions.direct(subject, conversation, peer.id());
    }
}
