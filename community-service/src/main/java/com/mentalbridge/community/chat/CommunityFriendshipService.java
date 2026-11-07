package com.mentalbridge.community.chat;

import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;

@Service
class CommunityFriendshipService {
    private final ChatAccountAuthorizer accounts;
    private final CommunityChatProfiles profiles;
    private final FriendshipTransactions transactions;

    CommunityFriendshipService(ChatAccountAuthorizer accounts, CommunityChatProfiles profiles, FriendshipTransactions transactions) {
        this.accounts = accounts; this.profiles = profiles; this.transactions = transactions;
    }

    FriendshipTransactions.Link request(UUID subject, UUID target, UUID correlation) {
        var peer = profiles.byId(target).filter(p -> p.active() && !p.subject().equals(subject)).orElseThrow(ChatException::target);
        accounts.require(List.of(subject, peer.subject()), correlation);
        return transactions.request(subject, target);
    }

    FriendshipTransactions.Link accept(UUID subject, UUID id, UUID correlation) {
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var peer = profiles.byId(transactions.target(id, actor.id())).orElseThrow(ChatException::target);
        accounts.require(List.of(subject, peer.subject()), correlation);
        return transactions.accept(subject, id);
    }

    void remove(UUID subject, UUID id, UUID correlation) {
        accounts.require(List.of(subject), correlation);
        transactions.remove(subject, id);
    }

    FriendshipTransactions.Page list(UUID subject, String view, UUID cursor, int limit, UUID correlation) {
        accounts.require(List.of(subject), correlation);
        return transactions.list(subject, view, cursor, limit);
    }
}
