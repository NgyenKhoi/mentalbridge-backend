package com.mentalbridge.community.chat;

import java.time.*;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "community_chat_report_case")
class ChatReportCaseEntity {
    @Id UUID id;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "message_id", nullable = false) UUID messageId;
    @Column(name = "reporter_profile_id", nullable = false) UUID reporterProfileId;
    @Column(name = "reason_code", nullable = false) String reasonCode;
    @Column(nullable = false) String priority;
    @Column(nullable = false) String state = "OPEN";
    @Column(name = "evidence_ciphertext", nullable = false) String evidenceCiphertext;
    @Column(name = "details_ciphertext", nullable = false) String detailsCiphertext;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "review_due_at", nullable = false) Instant reviewDueAt;
    protected ChatReportCaseEntity() {}
    ChatReportCaseEntity(UUID id, ChatMessageEntity message, UUID reporter, String reason, String evidence, String details, Instant now) {
        this.id = id; conversationId = message.conversationId; messageId = message.id; reporterProfileId = reporter;
        reasonCode = reason; priority = reason.equals("SELF_HARM_OR_CRISIS_CONCERN") ? "HIGH" : "NORMAL";
        evidenceCiphertext = evidence; detailsCiphertext = details; createdAt = now; reviewDueAt = now.plus(Duration.ofDays(30));
    }
}
