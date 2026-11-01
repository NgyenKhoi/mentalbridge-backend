package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

@ExtendWith(OutputCaptureExtension.class)
class CommunityChatReportIntegrationTests extends ChatGroupIntegrationSupport {
    @Autowired CommunityChatMessageService messages;
    @Autowired ChatCrypto crypto;
    UUID saved(UUID subject, UUID group, String text) {
        return messages.send(subject, group, UUID.randomUUID(), new ChatModels.SendMessage(UUID.randomUUID(), text), UUID.randomUUID()).message().messageId();
    }
    @Test
    void capturesOnlyExactReadableMessageAsEncryptedImmutableCaseAndIdempotentRetry(CapturedOutput logs) throws Exception {
        var group = group(); join(b, group, bp);
        var text = "Tin nhắn riêng tổng hợp được báo cáo."; var detail = "Chi tiết tổng hợp chỉ cho case.";
        var message = saved(a, group, text); saved(a, group, "Tin khác không thuộc evidence");
        var key = UUID.randomUUID(); var first = json(report(b, message, "SELF_HARM_OR_CRISIS_CONCERN", detail, key).andExpect(status().isCreated()));
        assertThat(json(report(b, message, "SELF_HARM_OR_CRISIS_CONCERN", detail, key).andExpect(status().isCreated()))).isEqualTo(first);
        assertThat(json(report(b, message, "OTHER", "Chi tiết khác", UUID.randomUUID()).andExpect(status().isCreated()))).isEqualTo(first);
        report(b, message, "OTHER", detail, key).andExpect(status().isConflict());
        assertThat(count("community_chat_report_case")).isOne();
        var row = jdbc.sql("select * from community_chat_report_case").query().singleRow();
        var id = first.path("caseId").asText();
        assertThat(row.get("priority")).isEqualTo("HIGH"); assertThat(row.get("state")).isEqualTo("OPEN");
        assertThat(row.get("evidence_ciphertext").toString()).doesNotContain(text).doesNotContain(detail);
        var evidence = crypto.open("chat-report-evidence:" + id, row.get("evidence_ciphertext").toString());
        assertThat(evidence).contains(text).doesNotContain("Tin khác không thuộc evidence").doesNotContain(detail);
        assertThat(crypto.open("chat-report-details:" + id, row.get("details_ciphertext").toString())).isEqualTo(detail);
        assertThat(logs.getAll()).doesNotContain(text).doesNotContain(detail);
        for (var wire : jdbc.sql("select event_payload::text from community_chat_outbox").query(String.class).list()) assertThat(wire).doesNotContain(text).doesNotContain(detail);
    }
    @Test
    void outsiderBlockedExpiredAndPriorEpochTargetsCannotCreateOrReplayReports() throws Exception {
        var group = group(); join(b, group, bp); var message = saved(a, group, "Nội dung tổng hợp");
        report(outsider, message, "SPAM", "", UUID.randomUUID()).andExpect(status().isNotFound());
        var key = UUID.randomUUID(); report(b, message, "SPAM", "", key).andExpect(status().isCreated());
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        report(b, message, "SPAM", "", key).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        remove(b, group, bp).andExpect(status().isNoContent()); join(b, group, bp);
        report(b, message, "SPAM", "", UUID.randomUUID()).andExpect(status().isNotFound());
        clock.advance(Duration.ofDays(90)); report(a, message, "SPAM", "", UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(count("community_chat_report_case")).isOne();
    }
    @Test
    void reportBudgetAndStrictBodyPreventAbuseWithoutGrantingAdminTranscriptAccess() throws Exception {
        var group = group(); join(b, group, bp);
        for (int index = 0; index < 10; index++) {
            clock.advance(Duration.ofSeconds(1)); report(b, saved(a, group, "Tin mẫu " + index), "SPAM", "", UUID.randomUUID()).andExpect(status().isCreated());
        }
        clock.advance(Duration.ofSeconds(1)); var target = saved(a, group, "Tin vượt giới hạn báo cáo");
        report(b, target, "SPAM", "", UUID.randomUUID()).andExpect(status().isTooManyRequests());
        report(b, target, "INVALID", "", UUID.randomUUID()).andExpect(status().isBadRequest());
        report(b, target, "SPAM", "x".repeat(1001), UUID.randomUUID()).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/reports").with(user(b)).header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageId\":\"" + target + "\",\"reasonCode\":\"SPAM\",\"actor\":\"spoof\"}")).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/admin/report-cases").with(user(b))).andExpect(status().isNotFound());
    }
    @Test
    void directMessageReportingUsesExistingBilateralAuthority() throws Exception {
        preference(b, "ANYONE", "0").andExpect(status().isOk());
        var direct = UUID.fromString(json(start(a, bp, UUID.randomUUID()).andExpect(status().isCreated())).path("conversationId").asText());
        var message = saved(a, direct, "Tin trực tiếp tổng hợp"); report(b, message, "HARASSMENT", "", UUID.randomUUID()).andExpect(status().isCreated());
        denied.add(a); report(b, message, "OTHER", "", UUID.randomUUID()).andExpect(status().isNotFound());
    }
    @Test
    void retainedMessagesRemainReportableAfterAuthorDepartureWithoutGrantingPriorEpochAccess() throws Exception {
        var group = group(); join(b, group, bp);
        var message = saved(b, group, "Tin tổng hợp trước khi rời nhóm");
        remove(b, group, bp).andExpect(status().isNoContent());
        var key = UUID.randomUUID();
        var result = json(report(a, message, "SPAM", "", key).andExpect(status().isCreated()));
        report(b, message, "SPAM", "", UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/community/blocks/{id}", bp).with(user(a))).andExpect(status().isNoContent());
        report(a, message, "SPAM", "", key).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/community/blocks/{id}", bp).with(user(a))).andExpect(status().isNoContent());
        join(b, group, bp);
        report(b, message, "SPAM", "", UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(json(report(a, message, "SPAM", "", key).andExpect(status().isCreated()))).isEqualTo(result);
        clock.advance(Duration.ofDays(90));
        report(a, message, "SPAM", "", key).andExpect(status().isNotFound());
    }
    @Test
    void sourceDeletionRemovesPrivateHistoryAndRetryIdentityWithoutErasingIsolatedReportEvidence() throws Exception {
        var group = group(); join(b, group, bp); var message = saved(a, group, "Tin nguồn tổng hợp");
        var result = json(report(b, message, "PRIVACY_CONCERN", "", UUID.randomUUID()).andExpect(status().isCreated()));
        jdbc.sql("delete from community_chat_conversation where id = :id").param("id", group).update();
        assertThat(count("community_chat_message")).isZero(); assertThat(count("community_chat_command")).isZero();
        assertThat(count("community_chat_report_case")).isOne();
        var evidence = jdbc.sql("select evidence_ciphertext from community_chat_report_case").query(String.class).single();
        assertThat(crypto.open("chat-report-evidence:" + result.path("caseId").asText(), evidence)).contains("Tin nguồn tổng hợp");
        report(b, message, "PRIVACY_CONCERN", "", UUID.randomUUID()).andExpect(status().isNotFound());
    }
    @Test
    void retractionLeavesAlreadyCapturedReportEvidenceUnchanged() throws Exception {
        var group = group(); join(b, group, bp);
        var content = "Nội dung riêng đã được chụp làm bằng chứng";
        var message = saved(a, group, content);
        var result = json(report(b, message, "PRIVACY_CONCERN", "", UUID.randomUUID()).andExpect(status().isCreated()));
        var before = jdbc.sql("select evidence_ciphertext from community_chat_report_case").query(String.class).single();

        mvc.perform(delete(ROOT + "/conversations/{conversation}/messages/{message}", group, message).with(user(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RETRACTED"))
                .andExpect(jsonPath("$.content").doesNotExist());

        var after = jdbc.sql("select evidence_ciphertext from community_chat_report_case").query(String.class).single();
        assertThat(after).isEqualTo(before);
        assertThat(crypto.open("chat-report-evidence:" + result.path("caseId").asText(), after)).contains(content);
    }
    ResultActions report(UUID actor, UUID message, String reason, String details, UUID key) throws Exception {
        return mvc.perform(post(ROOT + "/reports").with(user(actor)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("messageId", message, "reasonCode", reason, "details", details))));
    }
}
