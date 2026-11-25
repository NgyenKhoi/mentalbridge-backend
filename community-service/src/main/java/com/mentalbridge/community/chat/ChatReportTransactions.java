package com.mentalbridge.community.chat;

import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatReportTransactions {
    private final ChatGroupAccess groups;
    private final ChatMessageRepository messages;
    private final ChatConversationRepository conversations;
    private final ChatAccess direct;
    private final ChatReportCaseRepository reports;
    private final ChatCommandRepository commands;
    private final ChatCrypto crypto;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ChatHistoryVisibility visibility;
    ChatReportTransactions(ChatGroupAccess groups, ChatMessageRepository messages, ChatConversationRepository conversations,
            ChatAccess direct, ChatReportCaseRepository reports, ChatCommandRepository commands, ChatCrypto crypto,
            ObjectMapper mapper, Clock clock, ChatHistoryVisibility visibility) {
        this.groups = groups; this.messages = messages; this.conversations = conversations; this.direct = direct; this.reports = reports;
        this.commands = commands; this.crypto = crypto; this.mapper = mapper; this.clock = clock;
        this.visibility = visibility;
    }
    @Transactional
    ChatReportModels.Result capture(UUID conversation, UUID actor, UUID peer, ChatGroupAccess.Authorization auth, UUID key, ChatReportModels.Input input) {
        var message = messages.findById(input.messageId()).orElseThrow(ChatException::target);
        if (auth != null) {
            var context = groups.lock(conversation, auth, true);
            if (!groups.messageVisible(context, message)) throw ChatException.target();
        } else {
            direct.pair(actor, peer);
            var current = conversations.lock(conversation).orElseThrow(ChatException::target);
            if (!current.kind.equals("DIRECT") || !current.state.equals("ACTIVE") || !current.other(actor).equals(peer)
                    || !message.conversationId.equals(conversation)) throw ChatException.target();
        }
        if (!message.readable(clock.instant()) || !visibility.visible(conversation, actor, message)) throw ChatException.target();
        var normalized = input.messageId() + ":" + input.reasonCode() + ":" + input.details();
        var retry = commands.findByActorProfileIdAndOperationAndIdempotencyKey(actor, "REPORT_MESSAGE", key);
        if (retry.isPresent()) {
            var command = retry.orElseThrow();
            if (!crypto.matches(command.requestFingerprint, "REPORT_MESSAGE", normalized)) throw ChatException.conflict();
            var report = reports.findById(command.targetId).orElseThrow(ChatException::conflict);
            return new ChatReportModels.Result(report.id, "RECEIVED");
        }
        var existing = reports.findByReporterProfileIdAndMessageId(actor, message.id);
        ChatReportCaseEntity report;
        if (existing.isPresent()) report = existing.orElseThrow();
        else {
            if (reports.countByReporterProfileIdAndCreatedAtAfter(actor, clock.instant().minusSeconds(86400)) >= 10) throw ChatException.limited();
            var id = UUID.randomUUID();
            var content = crypto.open(ChatGroupMessageTransactions.purpose(conversation, message.id, message.senderProfileId), message.contentCiphertext);
            var evidence = mapper.valueToTree(Map.of("messageId", message.id, "conversationId", conversation,
                    "senderCommunityProfileId", message.senderProfileId, "content", content, "createdAt", message.createdAt)).toString();
            report = reports.save(new ChatReportCaseEntity(id, message, actor, input.reasonCode(),
                    crypto.seal("chat-report-evidence:" + id, evidence), crypto.seal("chat-report-details:" + id, input.details()), clock.instant()));
        }
        commands.save(new ChatCommandEntity(actor, "REPORT_MESSAGE", key, crypto.fingerprint("REPORT_MESSAGE", normalized), "REPORT", report.id, conversation, clock.instant()));
        return new ChatReportModels.Result(report.id, "RECEIVED");
    }
}
