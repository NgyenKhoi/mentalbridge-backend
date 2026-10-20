package com.mentalbridge.community.chat;

import java.text.Normalizer;
import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;

@Service
class CommunityChatMessageService {
    private final ChatConversationRepository conversations;
    private final CommunityChatProfiles profiles;
    private final ChatAccountAuthorizer accounts;
    private final ChatMessageTransactions transactions;
    private final ChatGroupAccess groups;
    private final ChatGroupMessageTransactions groupMessages;
    CommunityChatMessageService(ChatConversationRepository conversations, CommunityChatProfiles profiles,
            ChatAccountAuthorizer accounts, ChatMessageTransactions transactions, ChatGroupAccess groups, ChatGroupMessageTransactions groupMessages) {
        this.conversations = conversations; this.profiles = profiles; this.accounts = accounts; this.transactions = transactions;
        this.groups = groups; this.groupMessages = groupMessages;
    }
    ChatModels.SendResult send(UUID subject, UUID conversationId, UUID key, ChatModels.SendMessage request, UUID correlation) {
        if (request.clientMessageId() == null || request.content() == null) throw ChatException.invalid();
        var content = Normalizer.normalize(request.content(), Normalizer.Form.NFC);
        if (content.isBlank() || content.codePointCount(0, content.length()) > 4000
                || content.codePoints().anyMatch(point -> Character.getType(point) == Character.SURROGATE)) throw ChatException.invalid();
        if (group(conversationId)) return groupMessages.send(conversationId, groups.authorize(subject, conversationId, null, correlation), key,
                new ChatModels.SendMessage(request.clientMessageId(), content), correlation);
        var peer = peer(subject, conversationId, correlation);
        return transactions.send(subject, conversationId, peer.id(), key, new ChatModels.SendMessage(request.clientMessageId(), content), correlation);
    }
    ChatModels.Page<ChatModels.Message> history(UUID subject, UUID conversationId, String cursor, int limit, UUID correlation) {
        ChatModels.limit(limit);
        if (group(conversationId)) return groupMessages.history(conversationId, groups.authorize(subject, conversationId, null, correlation), cursor, limit);
        var peer = peer(subject, conversationId, correlation);
        return transactions.history(subject, conversationId, peer.id(), cursor, limit);
    }
    ChatModels.ReadResult read(UUID subject, UUID conversationId, UUID messageId, UUID correlation) {
        if (messageId == null) throw ChatException.invalid();
        if (group(conversationId)) return groupMessages.read(conversationId, groups.authorize(subject, conversationId, null, correlation), messageId, correlation);
        var peer = peer(subject, conversationId, correlation);
        return transactions.read(subject, conversationId, peer.id(), messageId, correlation);
    }
    ChatModels.Message projection(UUID subject, UUID conversation, UUID id, UUID correlation) {
        if (group(conversation)) return groupMessages.projection(conversation, groups.authorize(subject, conversation, null, correlation), id);
        return transactions.projection(subject, conversation, peer(subject, conversation, correlation).id(), id);
    }
    ChatDeliveryCursor.Projection replay(UUID subject, UUID conversation, String cursor, UUID correlation) {
        if (group(conversation)) return groupMessages.replay(conversation, groups.authorize(subject, conversation, null, correlation), cursor);
        return transactions.replay(subject, conversation, peer(subject, conversation, correlation).id(), cursor);
    }
    private boolean group(UUID id) { return conversations.findById(id).orElseThrow(ChatException::target).kind.equals("GROUP"); }
    private CommunityChatProfiles.Profile peer(UUID subject, UUID conversationId, UUID correlation) {
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var conversation = conversations.findById(conversationId).orElseThrow(ChatException::target);
        var peer = profiles.byId(conversation.other(actor.id())).orElseThrow(ChatException::target);
        accounts.require(List.of(subject, peer.subject()), correlation);
        return peer;
    }
}
