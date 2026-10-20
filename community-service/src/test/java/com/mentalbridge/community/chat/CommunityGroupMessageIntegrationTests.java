package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CommunityGroupMessageIntegrationTests extends ChatGroupIntegrationSupport {
    @Test
    void invitationAloneCannotReadSendOrAcknowledgeAndOnlyCurrentMemberListsTheGroup() throws Exception {
        var group = group(); var invite = invite(a, group, bp);
        send(b, group, UUID.randomUUID(), UUID.randomUUID(), "Không được ghi").andExpect(status().isNotFound());
        history(b, group, null, 20).andExpect(status().isNotFound());
        conversation(b, group).andExpect(status().isNotFound());
        var before = json(mvc.perform(get(ROOT + "/conversations").with(user(b))).andExpect(status().isOk()));
        assertThat(before.path("items").size()).isZero();
        decide(b, invite, "ACCEPT", 0).andExpect(status().isOk());
        assertThat(json(mvc.perform(get(ROOT + "/conversations").with(user(b))).andExpect(status().isOk())).path("items").size()).isOne();
        assertThat(json(conversation(b, group).andExpect(status().isOk())).path("viewerRole").asText()).isEqualTo("MEMBER");
    }
    @Test
    void concurrentRetriesCommitOneEncryptedMessageAndExactRecipientFactsWithoutBumpingManagementVersion() throws Exception {
        var group = group(); join(b, group, bp); join(outsider, group, profile(outsider));
        var version = version(group); var key = UUID.randomUUID(); var client = UUID.randomUUID();
        var text = "Tin nhắn nhóm riêng tổng hợp.";
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(i -> (Callable<Integer>) () -> send(a, group, client, key, text).andReturn().getResponse().getStatus()).toList();
            for (var result : executor.invokeAll(tasks)) assertThat(result.get()).isEqualTo(201);
        }
        assertThat(count("community_chat_message")).isOne(); assertThat(version(group)).isEqualTo(version);
        assertThat(json(conversation(b, group).andExpect(status().isOk())).path("unreadCount").asInt()).isOne();
        assertThat(json(conversation(a, group).andExpect(status().isOk())).path("unreadCount").asInt()).isZero();
        assertThat(jdbc.sql("select content_ciphertext from community_chat_message").query(String.class).single()).doesNotContain(text);
        var facts = jdbc.sql("select event_payload::text from community_chat_outbox where event_payload->'payload'->>'kind' = 'MESSAGE_RECEIVED'").query(String.class).list();
        assertThat(facts).hasSize(2);
        for (var fact : facts) { assertThat(ChatWireContracts.validFact(fact)).isTrue(); assertThat(fact).doesNotContain(text); }
        send(a, group, client, UUID.randomUUID(), "Nội dung khác").andExpect(status().isConflict());
    }
    @Test
    void leaveAndRejoinNeverRestoreEarlierMessagesReadReferencesOrRetryRights() throws Exception {
        var group = group(); join(b, group, bp);
        var client = UUID.randomUUID(); var key = UUID.randomUUID();
        var mine = json(send(b, group, client, key, "Tin trước khi rời").andExpect(status().isCreated()));
        var id = UUID.fromString(mine.path("message").path("messageId").asText());
        var first = saved(a, group); var second = saved(a, group);
        var cursor = json(history(b, group, null, 1).andExpect(status().isOk())).path("nextCursor").asText();
        read(b, group, second).andExpect(status().isOk());
        remove(b, group, bp).andExpect(status().isNoContent());
        history(b, group, null, 20).andExpect(status().isNotFound()); read(b, group, first).andExpect(status().isNotFound());
        send(b, group, client, key, "Tin trước khi rời").andExpect(status().isNotFound());
        join(b, group, bp);
        assertThat(json(history(b, group, null, 20).andExpect(status().isOk())).path("items").size()).isZero();
        assertThat(json(conversation(b, group).andExpect(status().isOk())).path("unreadCount").asInt()).isZero();
        history(b, group, cursor, 20).andExpect(status().isNotFound()); read(b, group, id).andExpect(status().isNotFound());
        read(b, group, second).andExpect(status().isNotFound());
        send(b, group, client, key, "Tin trước khi rời").andExpect(status().isConflict());
        var fresh = saved(a, group); read(b, group, fresh).andExpect(status().isOk());
        assertThat(count("community_chat_membership")).isEqualTo(3);
    }
    @Test
    void blockFiltersBeforeHistoryUnreadPaginationAndMemberProjectionAndUnblockRequiresFreshCursor() throws Exception {
        var group = group(); join(b, group, bp); join(outsider, group, profile(outsider));
        var first = saved(a, group); saved(outsider, group);
        var oldCursor = json(history(b, group, null, 1).andExpect(status().isOk())).path("nextCursor").asText();
        read(b, group, first).andExpect(status().isOk());
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        var version = version(group); var before = json(conversation(b, group).andExpect(status().isOk()));
        var hidden = saved(a, group);
        var after = json(conversation(b, group).andExpect(status().isOk()));
        assertThat(after).isEqualTo(before); assertThat(version(group)).isEqualTo(version);
        read(b, group, hidden).andExpect(status().isNotFound());
        var history = json(history(b, group, null, 1).andExpect(status().isOk()));
        assertThat(history.path("items").size()).isOne(); assertThat(history.path("nextCursor").isNull()).isTrue();
        history(b, group, oldCursor, 1).andExpect(status().isNotFound());
        var memberList = json(members(b, group).andExpect(status().isOk())).path("items");
        assertThat(memberList.toString()).contains("UNAVAILABLE").doesNotContain(ap.toString());
        var read = json(read(b, group, UUID.fromString(history.path("items").get(0).path("messageId").asText())).andExpect(status().isOk()));
        assertThat(read.path("unreadCount").asInt()).isZero();
        var current = json(history(b, group, null, 1).andExpect(status().isOk()));
        mvc.perform(delete("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        assertThat(json(history(b, group, null, 20).andExpect(status().isOk())).path("items").size()).isEqualTo(3);
        assertThat(json(conversation(b, group).andExpect(status().isOk())).path("unreadCount").asInt()).isOne();
        assertThat(jdbc.sql("select count(*) from community_chat_outbox where event_payload->'payload'->>'kind' = 'MESSAGE_RECEIVED' and event_payload->'payload'->>'targetId' = :id")
                .param("id", hidden.toString()).query(Long.class).single()).isOne();
    }
    @Test
    void hiddenMessagesDoNotReorderConversationListAndVisibilityCursorIsBoundToCurrentActor() throws Exception {
        var group = group(); join(b, group, bp); join(outsider, group, profile(outsider)); saved(outsider, group);
        clock.advance(Duration.ofSeconds(1)); var second = group(); join(b, second, bp); saved(a, second);
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        var before = json(mvc.perform(get(ROOT + "/conversations").with(user(b))).andExpect(status().isOk()));
        clock.advance(Duration.ofSeconds(1)); saved(a, group);
        var after = json(mvc.perform(get(ROOT + "/conversations").with(user(b))).andExpect(status().isOk()));
        assertThat(after).isEqualTo(before);
        var page = json(mvc.perform(get(ROOT + "/conversations").with(user(b)).param("limit", "1")).andExpect(status().isOk()));
        var cursor = page.path("nextCursor").asText();
        mvc.perform(get(ROOT + "/conversations").with(user(a)).param("cursor", cursor)).andExpect(status().isNotFound());
    }
    @Test
    void archivePreservesCurrentEligibleHistoryAndReadStateButForbidsSendsAndRemovedReads() throws Exception {
        var group = group(); join(b, group, bp); var message = saved(a, group);
        mvc.perform(delete(ROOT + "/groups/{id}", group).with(user(a)).header("If-Match", quoted(version(group)))).andExpect(status().isNoContent());
        history(b, group, null, 20).andExpect(status().isOk()); read(b, group, message).andExpect(status().isOk());
        send(a, group, UUID.randomUUID(), UUID.randomUUID(), "Đã lưu trữ").andExpect(status().isConflict());
        remove(b, group, bp).andExpect(status().isNoContent()); history(b, group, null, 20).andExpect(status().isNotFound());
    }
    @Test
    void currentSuspensionFiltersPeerTrafficAndDeniesActorIncludingSavedRetries() throws Exception {
        var group = group(); join(b, group, bp); var client = UUID.randomUUID(); var key = UUID.randomUUID();
        send(a, group, client, key, "Mẫu").andExpect(status().isCreated()); denied.add(a);
        assertThat(json(history(b, group, null, 20).andExpect(status().isOk())).path("items").size()).isZero();
        assertThat(json(conversation(b, group).andExpect(status().isOk())).path("unreadCount").asInt()).isZero();
        send(a, group, client, key, "Mẫu").andExpect(status().isNotFound());
    }
    UUID saved(UUID actor, UUID group) throws Exception { return UUID.fromString(json(send(actor, group, UUID.randomUUID(), UUID.randomUUID(), "Tin nhắn mẫu").andExpect(status().isCreated())).path("message").path("messageId").asText()); }
    ResultActions send(UUID actor, UUID group, UUID client, UUID key, String content) throws Exception {
        return mvc.perform(post(ROOT + "/conversations/{id}/messages", group).with(user(actor)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("clientMessageId", client, "content", content))));
    }
    ResultActions history(UUID actor, UUID group, String cursor, int limit) throws Exception {
        var request = get(ROOT + "/conversations/{id}/messages", group).with(user(actor)).param("limit", Integer.toString(limit));
        if (cursor != null) request.param("cursor", cursor); return mvc.perform(request);
    }
    ResultActions read(UUID actor, UUID group, UUID message) throws Exception {
        return mvc.perform(put(ROOT + "/conversations/{id}/read-cursor", group).with(user(actor)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("lastReadMessageId", message))));
    }
    ResultActions conversation(UUID actor, UUID group) throws Exception { return mvc.perform(get(ROOT + "/conversations/{id}", group).with(user(actor))); }
}
