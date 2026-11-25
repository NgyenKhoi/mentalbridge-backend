package com.mentalbridge.community.chat;

import java.text.Normalizer;
import java.util.*;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;

@Service
class CommunityChatReportService {
    private final ChatGroupAccess groups;
    private final ChatMessageRepository messages;
    private final ChatConversationRepository conversations;
    private final CommunityChatProfiles profiles;
    private final ChatAccountAuthorizer accounts;
    private final ChatReportTransactions reports;
    CommunityChatReportService(ChatGroupAccess groups, ChatMessageRepository messages, ChatConversationRepository conversations,
            CommunityChatProfiles profiles, ChatAccountAuthorizer accounts, ChatReportTransactions reports) {
        this.groups = groups; this.messages = messages; this.conversations = conversations; this.profiles = profiles; this.accounts = accounts; this.reports = reports;
    }
    ChatReportModels.Result report(UUID subject, UUID key, ChatReportModels.Input input, UUID correlation) {
        if (input.messageId() == null || !Set.of("HARASSMENT", "SPAM", "PRIVACY_CONCERN", "SELF_HARM_OR_CRISIS_CONCERN", "OTHER")
                .contains(input.reasonCode() == null ? "" : input.reasonCode())) throw ChatException.invalid();
        var details = input.details() == null ? "" : Normalizer.normalize(input.details(), Normalizer.Form.NFC);
        if (details.codePointCount(0, details.length()) > 1000 || details.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) throw ChatException.invalid();
        var normalized = new ChatReportModels.Input(input.messageId(), input.reasonCode(), details);
        var message = messages.findById(input.messageId()).orElseThrow(ChatException::target);
        var conversation = conversations.findById(message.conversationId).orElseThrow(ChatException::target);
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        if (conversation.kind.equals("GROUP")) return reports.capture(conversation.id, actor.id(), null,
                groups.authorize(subject, conversation.id, message.senderProfileId, correlation), key, normalized);
        var peer = profiles.byId(conversation.other(actor.id())).orElseThrow(ChatException::target);
        accounts.require(List.of(subject, peer.subject()), correlation);
        return reports.capture(conversation.id, actor.id(), peer.id(), null, key, normalized);
    }
}
