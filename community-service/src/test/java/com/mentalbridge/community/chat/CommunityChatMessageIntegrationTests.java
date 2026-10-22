package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class CommunityChatMessageIntegrationTests extends ChatIntegrationSupport {
    @Autowired ChatRetention retention;
    @Autowired CommunityChatVisibility visibility;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void commitsEncryptedContentAndAcknowledgesOneMessageWithoutClaimingDelivery(CapturedOutput output) throws Exception {
        var conversation = active(a, bp);
        var clientId = UUID.randomUUID(); var key = UUID.randomUUID();
        var text = "Nội dung tổng hợp riêng tư cho regression MB-685, không đưa vào log.";
        var first = json(send(a, conversation, clientId, key, text).andExpect(status().isCreated())
                .andExpect(jsonPath("$.persistence").value("SAVED")).andExpect(jsonPath("$.delivery").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.message.sequence").doesNotExist()));
        var messageId = first.path("message").path("messageId").asText();
        assertThat(first.path("message").size()).isEqualTo(7);
        assertThat(first.path("message").path("state").asText()).isEqualTo("ACTIVE");
        assertThat(first.path("message").path("sender").path("communityProfileId").asText()).isEqualTo(ap.toString());
        assertThat(json(send(a, conversation, clientId, key, text).andExpect(status().isCreated())).path("message").path("messageId").asText()).isEqualTo(messageId);
        assertThat(json(send(a, conversation, clientId, UUID.randomUUID(), text).andExpect(status().isCreated())).path("message").path("messageId").asText()).isEqualTo(messageId);
        assertThat(count("community_chat_message")).isOne();
        assertThat(sequence(conversation)).isOne();
        assertThat(unread(b, conversation)).isOne(); assertThat(unread(a, conversation)).isZero();
        mvc.perform(get(ROOT + "/conversations/{id}", conversation).with(user(a))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1")).andExpect(jsonPath("$.lastSequence").doesNotExist());
        var ciphertext = jdbc.sql("select content_ciphertext from community_chat_message").query(String.class).single();
        assertThat(ciphertext).startsWith("test-v1.").doesNotContain(text);
        assertThat(jdbc.sql("select count(*) from community_chat_outbox where event_payload->'payload'->>'kind' = 'MESSAGE_SAVED'").query(Long.class).single()).isOne();
        for (var wire : jdbc.sql("select event_payload::text from community_chat_outbox").query(String.class).list()) {
            assertThat(ChatWireContracts.validFact(wire)).isTrue();
            assertThat(wire).doesNotContain(text).doesNotContain("Tên cộng đồng mẫu");
            var event = mapper.readTree(wire); var payload = event.path("payload");
            assertThat(payload.has("content")).isFalse();
            if (payload.path("kind").asText().equals("MESSAGE_RECEIVED")) {
                assertThat(payload.path("recipientRoutingReference").asText()).isEqualTo(b.toString());
                assertThat(payload.path("actorCommunityProfileId").asText()).isEqualTo(ap.toString());
                assertThat(payload.path("targetId").asText()).isEqualTo(messageId);
            }
        }
        assertThat(output.getAll()).doesNotContain(text);
    }

    @Test
    void rejectsChangedContentClientIdentityAndCrossConversationKeyReuse() throws Exception {
        var conversation = active(a, bp); var clientId = UUID.randomUUID(); var key = UUID.randomUUID();
        send(a, conversation, clientId, key, "Mẫu một").andExpect(status().isCreated());
        send(a, conversation, clientId, key, "Mẫu khác").andExpect(status().isConflict());
        send(a, conversation, clientId, UUID.randomUUID(), "Mẫu khác").andExpect(status().isConflict());
        send(a, conversation, UUID.randomUUID(), key, "Mẫu một").andExpect(status().isConflict());
        var otherProfile = jdbc.sql("select id from community_profile where account_subject = :subject").param("subject", outsider).query(UUID.class).single();
        var otherConversation = active(a, otherProfile);
        send(a, otherConversation, clientId, key, "Mẫu một").andExpect(status().isConflict());
        assertThat(count("community_chat_message")).isOne();
        assertThat(sequence(conversation)).isOne(); assertThat(sequence(otherConversation)).isZero();
    }

    @Test
    void concurrentSameCommandHasOneMessageSequenceOutboxAndUnreadEffect() throws Exception {
        var conversation = active(a, bp); var client = UUID.randomUUID(); var key = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 12; i++) tasks.add(() -> json(send(a, conversation, client, key, "Tin nhắn retry đồng thời")
                    .andExpect(status().isCreated())).path("message").path("messageId").asText());
            var ids = new HashSet<String>(); for (var result : executor.invokeAll(tasks)) ids.add(result.get());
            assertThat(ids).hasSize(1);
        }
        assertThat(count("community_chat_message")).isOne(); assertThat(sequence(conversation)).isOne();
        assertThat(count("community_chat_outbox")).isEqualTo(3); assertThat(unread(b, conversation)).isOne();
    }

    @Test
    void enforcesBurstAndMinuteLimitsAcrossDevicesWhileCommittedRetriesStillWork() throws Exception {
        var conversation = active(a, bp); var firstClient = UUID.randomUUID(); var firstKey = UUID.randomUUID();
        send(a, conversation, firstClient, firstKey, "Mẫu giới hạn").andExpect(status().isCreated());
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 9; i++) tasks.add(() -> send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Mẫu burst")
                    .andReturn().getResponse().getStatus());
            for (var result : executor.invokeAll(tasks)) assertThat(result.get()).isEqualTo(201);
        }
        send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Vượt burst").andExpect(status().isTooManyRequests());
        for (int round = 0; round < 5; round++) {
            clock.advance(Duration.ofSeconds(6));
            for (int i = 0; i < 10; i++) send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Mẫu minute")
                    .andExpect(status().isCreated());
        }
        clock.advance(Duration.ofSeconds(6));
        send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Vượt phút").andExpect(status().isTooManyRequests());
        send(a, conversation, firstClient, firstKey, "Mẫu giới hạn").andExpect(status().isCreated());
        assertThat(count("community_chat_message")).isEqualTo(60);
        assertThat(sequence(conversation)).isEqualTo(60);
        clock.advance(Duration.ofSeconds(61));
        send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Cửa sổ mới").andExpect(status().isCreated());
    }

    @Test
    void textBoundsUseUnicodeAndClientCannotSetSenderOrDelivery() throws Exception {
        var conversation = active(a, bp);
        for (var text : List.of("", " \n\t", "x".repeat(4001))) {
            send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), text).andExpect(status().isBadRequest());
        }
        mvc.perform(post(ROOT + "/conversations/{id}/messages", conversation).with(user(a)).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"content\":\"\\ud800\"}"))
                .andExpect(status().isBadRequest());
        for (var extra : List.of("senderId", "actorId", "attachments", "delivery")) {
            mvc.perform(post(ROOT + "/conversations/{id}/messages", conversation).with(user(a)).header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(),
                            "content", "Mẫu không được spoof", extra, "forged"))))
                    .andExpect(status().isBadRequest());
        }
        send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "á".repeat(4000)).andExpect(status().isCreated());
        send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "😀".repeat(4000)).andExpect(status().isCreated());
        assertThat(count("community_chat_message")).isEqualTo(2);
    }

    @Test
    void historyIsOrderedSnapshotPagedAndCursorCannotSelectAnotherViewerOrConversation() throws Exception {
        var conversation = active(a, bp); var ids = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            ids.add(json(send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Mẫu trang " + i)
                    .andExpect(status().isCreated())).path("message").path("messageId").asText());
            clock.advance(Duration.ofSeconds(1));
        }
        var page = json(history(b, conversation, null, 2).andExpect(status().isOk()));
        assertThat(page.path("items").get(0).path("messageId").asText()).isEqualTo(ids.get(0));
        assertThat(page.path("items").get(1).path("messageId").asText()).isEqualTo(ids.get(1));
        var cursor = page.path("nextCursor").asText();
        assertThat(cursor).isNotBlank().hasSizeLessThanOrEqualTo(1024).doesNotContain(conversation.toString());
        send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Tin mới sau snapshot").andExpect(status().isCreated());
        var next = json(history(b, conversation, cursor, 2).andExpect(status().isOk()));
        assertThat(next.path("items").size()).isOne();
        assertThat(next.path("items").get(0).path("messageId").asText()).isEqualTo(ids.get(2));
        assertThat(next.path("nextCursor").isNull()).isTrue();
        history(a, conversation, cursor, 2).andExpect(status().isNotFound());
        history(b, conversation, cursor + "x", 2).andExpect(status().isNotFound());
        var otherConversation = active(outsider, bp);
        history(b, otherConversation, cursor, 2).andExpect(status().isNotFound());
        history(b, conversation, null, 50).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4));
    }

    @Test
    void privateReadCursorIsMonotonicMessageBoundAndIndependentOfSharedReceiptPreference() throws Exception {
        var conversation = active(a, bp); var ids = new ArrayList<UUID>();
        for (int i = 0; i < 3; i++) ids.add(UUID.fromString(json(send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Mẫu đã đọc " + i)
                .andExpect(status().isCreated())).path("message").path("messageId").asText()));
        assertThat(unread(b, conversation)).isEqualTo(3);
        read(b, conversation, ids.getLast()).andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
        read(b, conversation, ids.getFirst()).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadMessageId").value(ids.getLast().toString()));
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (var result : executor.invokeAll(List.<Callable<Integer>>of(
                    () -> read(b, conversation, ids.get(0)).andReturn().getResponse().getStatus(),
                    () -> read(b, conversation, ids.get(1)).andReturn().getResponse().getStatus()))) assertThat(result.get()).isEqualTo(200);
        }
        assertThat(jdbc.sql("select last_read_sequence from community_chat_read_state where profile_id = :viewer")
                .param("viewer", bp).query(Long.class).single()).isEqualTo(3);
        read(b, conversation, UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(put(ROOT + "/conversations/{id}/read-cursor", conversation).with(user(b)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastReadSequence\":\"3\"}")).andExpect(status().isBadRequest());
        send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Mẫu sau watermark").andExpect(status().isCreated());
        assertThat(unread(b, conversation)).isOne();
        var outgoing = UUID.fromString(json(send(b, conversation, UUID.randomUUID(), UUID.randomUUID(), "Mẫu phản hồi")
                .andExpect(status().isCreated())).path("message").path("messageId").asText());
        assertThat(unread(b, conversation)).isOne(); assertThat(unread(a, conversation)).isOne();
        read(b, conversation, outgoing).andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(get(ROOT + "/privacy").with(user(b))).andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences.readReceiptsVisible").value(false));
    }

    @Test
    void blockAndCurrentAccountStateOverrideHistorySendReadAndIdempotentReplay() throws Exception {
        var conversation = active(a, bp); var key = UUID.randomUUID(); var client = UUID.randomUUID();
        var id = UUID.fromString(json(send(a, conversation, client, key, "Mẫu trước chặn").andExpect(status().isCreated()))
                .path("message").path("messageId").asText());
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        send(a, conversation, client, key, "Mẫu trước chặn").andExpect(status().isNotFound());
        history(a, conversation, null, 20).andExpect(status().isNotFound());
        history(b, conversation, null, 20).andExpect(status().isNotFound());
        read(b, conversation, id).andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/conversations").with(user(a))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(delete("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        preference(b, "APPROVAL_REQUIRED", "1").andExpect(status().isOk());
        history(b, conversation, null, 20).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Mẫu accepted sau đổi privacy").andExpect(status().isCreated());
        denied.add(a);
        history(b, conversation, null, 20).andExpect(status().isNotFound());
        send(a, conversation, client, key, "Mẫu trước chặn").andExpect(status().isNotFound());
        read(b, conversation, id).andExpect(status().isNotFound());
        assertThat(count("community_chat_message")).isEqualTo(2);
    }

    @Test
    void expiredContentIsHiddenPurgedAndNeverRecreatedByLateRetries() throws Exception {
        var conversation = active(a, bp); var key = UUID.randomUUID(); var client = UUID.randomUUID();
        var id = UUID.fromString(json(send(a, conversation, client, key, "Mẫu retention").andExpect(status().isCreated()))
                .path("message").path("messageId").asText());
        read(b, conversation, id).andExpect(status().isOk());
        jdbc.sql("update community_chat_message set created_at = :created, expires_at = :expired")
                .param("created", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(91))))
                .param("expired", java.sql.Timestamp.from(clock.instant().minusSeconds(1))).update();
        history(b, conversation, null, 1).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        assertThat(unread(b, conversation)).isZero();
        read(b, conversation, id).andExpect(status().isNotFound());
        assertThat(retention.purgeBatch()).isOne(); assertThat(retention.purgeBatch()).isZero();
        assertThat(jdbc.sql("select count(*) from community_chat_message where content_ciphertext is null and purged_at is not null").query(Long.class).single()).isOne();
        send(a, conversation, client, key, "Mẫu retention").andExpect(status().isConflict());
        send(a, conversation, client, UUID.randomUUID(), "Mẫu retention").andExpect(status().isConflict());
        assertThat(count("community_chat_message")).isOne(); assertThat(sequence(conversation)).isOne();
        assertThat(jdbc.sql("select last_read_sequence from community_chat_read_state where profile_id = :viewer")
                .param("viewer", bp).query(Long.class).single()).isOne();
    }

    @Test
    void senderRetractionErasesContentKeepsPositionAndIsIdempotentWithoutUnread() throws Exception {
        var conversation = active(a, bp);
        var client = UUID.randomUUID(); var key = UUID.randomUUID();
        var first = json(send(a, conversation, client, key, "Nội dung riêng cần gỡ").andExpect(status().isCreated()));
        var id = UUID.fromString(first.path("message").path("messageId").asText());
        assertThat(unread(b, conversation)).isOne();

        var retracted = json(retract(a, conversation, id).andExpect(status().isOk()));
        assertThat(retracted.path("messageId").asText()).isEqualTo(id.toString());
        assertThat(retracted.path("state").asText()).isEqualTo("RETRACTED");
        assertThat(retracted.has("content")).isFalse();
        assertThat(retracted.path("retractedAt").asText()).isNotBlank();
        assertThat(unread(b, conversation)).isZero();
        assertThat(sequence(conversation)).isOne();

        var history = json(history(b, conversation, null, 20).andExpect(status().isOk()));
        assertThat(history.path("items").size()).isOne();
        assertThat(history.path("items").get(0)).isEqualTo(retracted);
        read(b, conversation, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadMessageId").value(id.toString()))
                .andExpect(jsonPath("$.unreadCount").value(0));

        var factCount = jdbc.sql("select count(*) from community_chat_outbox where event_payload->'payload'->>'kind' = 'MESSAGE_RETRACTED'")
                .query(Long.class).single();
        assertThat(factCount).isOne();
        assertThat(jdbc.sql("select state from community_chat_message where id = :id").param("id", id).query(String.class).single())
                .isEqualTo("RETRACTED");
        assertThat(jdbc.sql("select content_ciphertext from community_chat_message where id = :id").param("id", id).query(String.class).optional())
                .isEmpty();

        assertThat(json(retract(a, conversation, id).andExpect(status().isOk()))).isEqualTo(retracted);
        assertThat(jdbc.sql("select count(*) from community_chat_outbox where event_payload->'payload'->>'kind' = 'MESSAGE_RETRACTED'")
                .query(Long.class).single()).isOne();
        retract(b, conversation, id).andExpect(status().isNotFound());
        send(a, conversation, client, key, "Nội dung riêng cần gỡ").andExpect(status().isConflict());
    }

    @Test
    void failedOutboxCommitRollsBackMessageSequenceAndRetryIdentity() throws Exception {
        var conversation = active(a, bp); var key = UUID.randomUUID(); var client = UUID.randomUUID();
        jdbc.sql("alter table community_chat_outbox add constraint test_chat_outbox_atomicity check (event_payload->'payload'->>'kind' <> 'MESSAGE_RECEIVED')").update();
        try {
            send(a, conversation, client, key, "Mẫu rollback").andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
            assertThat(count("community_chat_message")).isZero(); assertThat(sequence(conversation)).isZero();
            assertThat(jdbc.sql("select count(*) from community_chat_command where operation = 'SEND_MESSAGE'").query(Long.class).single()).isZero();
        } finally { jdbc.sql("alter table community_chat_outbox drop constraint test_chat_outbox_atomicity").update(); }
        send(a, conversation, client, key, "Mẫu rollback").andExpect(status().isCreated());
        assertThat(count("community_chat_message")).isOne(); assertThat(sequence(conversation)).isOne();
    }

    @Test
    void aCommittedBlockWinsWhenSendWasWaitingForTheSameProfileLocks() throws Exception {
        var conversation = active(a, bp); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var accountChecked = new CountDownLatch(1);
        doAnswer(call -> {
            var query = call.getArgument(2, ChatIdentityHttpClient.Query.class); accountChecked.countDown();
            return new ChatIdentityHttpClient.Result(query.accountIds());
        }).when(identity).authorize(anyString(), anyString(), any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var block = executor.submit(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
                visibility.changeBlock(bp, ap, true, UUID.randomUUID()); locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Block fixture release timed out"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                return true;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var send = executor.submit(() -> send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Mẫu racing block").andReturn().getResponse().getStatus());
            assertThat(accountChecked.await(10, TimeUnit.SECONDS)).isTrue(); release.countDown();
            assertThat(block.get()).isTrue(); assertThat(send.get()).isEqualTo(404);
        } finally { release.countDown(); }
        assertThat(count("community_chat_message")).isZero(); assertThat(sequence(conversation)).isZero();
    }

    @Test
    void pendingOrUnrelatedRelationshipsCannotReadSendOrAcknowledgeMessages() throws Exception {
        var pending = pending(a, bp);
        var hiddenConversation = jdbc.sql("select conversation_id from community_chat_request where id = :id").param("id", pending).query(UUID.class).single();
        send(a, hiddenConversation, UUID.randomUUID(), UUID.randomUUID(), "Không gửi trước accept").andExpect(status().isNotFound());
        history(b, hiddenConversation, null, 20).andExpect(status().isNotFound());
        decision(b, pending, "ACCEPT", "0").andExpect(status().isOk());
        var message = UUID.fromString(json(send(a, hiddenConversation, UUID.randomUUID(), UUID.randomUUID(), "Mẫu chỉ participants")
                .andExpect(status().isCreated())).path("message").path("messageId").asText());
        history(outsider, hiddenConversation, null, 20).andExpect(status().isNotFound());
        send(outsider, hiddenConversation, UUID.randomUUID(), UUID.randomUUID(), "Outsider").andExpect(status().isNotFound());
        read(outsider, hiddenConversation, message).andExpect(status().isNotFound());
        var second = active(outsider, bp);
        read(b, second, message).andExpect(status().isNotFound());
    }

    @Test
    void conversationOrderUsesOnlyCurrentEligibleActivityAndNoGlobalWatermark() throws Exception {
        var first = active(a, bp); clock.advance(Duration.ofSeconds(1));
        var other = jdbc.sql("select id from community_profile where account_subject = :subject").param("subject", outsider).query(UUID.class).single();
        var second = active(a, other); clock.advance(Duration.ofSeconds(1));
        send(a, first, UUID.randomUUID(), UUID.randomUUID(), "Mẫu mới nhất").andExpect(status().isCreated());
        mvc.perform(get(ROOT + "/conversations").with(user(a))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].conversationId").value(first.toString()))
                .andExpect(jsonPath("$.items[0].lastSequence").doesNotExist());
        jdbc.sql("update community_chat_message set created_at = :created, expires_at = :expired")
                .param("created", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(91))))
                .param("expired", java.sql.Timestamp.from(clock.instant().minusSeconds(1))).update();
        mvc.perform(get(ROOT + "/conversations").with(user(a))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].conversationId").value(second.toString()));
    }

    @Test
    void blockUnblockInvalidatesOldMessageCursorsAndExpiredGapsCreateNoExtraPage() throws Exception {
        var conversation = active(a, bp);
        for (int i = 0; i < 3; i++) send(a, conversation, UUID.randomUUID(), UUID.randomUUID(), "Mẫu khoảng trống " + i)
                .andExpect(status().isCreated());
        var cursor = json(history(b, conversation, null, 1).andExpect(status().isOk())).path("nextCursor").asText();
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        history(b, conversation, cursor, 1).andExpect(status().isNotFound());
        jdbc.sql("update community_chat_message set created_at = :created, expires_at = :expired where server_sequence < 3")
                .param("created", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(91))))
                .param("expired", java.sql.Timestamp.from(clock.instant().minusSeconds(1))).update();
        history(b, conversation, null, 1).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sequence").doesNotExist()).andExpect(jsonPath("$.nextCursor").isEmpty());
        assertThat(unread(b, conversation)).isOne();
    }

    @Test
    void concurrentPreferenceChangesHaveDistinctCommittedAuthorizationVersions() throws Exception {
        var conversation = active(a, bp);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var results = executor.invokeAll(List.<Callable<Integer>>of(
                    () -> { barrier.await(); return preference(a, "ANYONE", "0").andReturn().getResponse().getStatus(); },
                    () -> { barrier.await(); return preference(b, "APPROVAL_REQUIRED", "1").andReturn().getResponse().getStatus(); }));
            for (var result : results) assertThat(result.get()).isEqualTo(200);
        }
        assertThat(jdbc.sql("select event_version from community_chat_conversation where id = :id")
                .param("id", conversation).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select count(distinct event_payload->>'aggregateVersion') from community_chat_outbox where conversation_id = :id")
                .param("id", conversation).query(Long.class).single()).isEqualTo(3);
    }

    UUID active(UUID actor, UUID target) throws Exception {
        var subject = jdbc.sql("select account_subject from community_profile where id = :id").param("id", target).query(UUID.class).single();
        var version = json(mvc.perform(get(ROOT + "/privacy").with(user(subject))).andExpect(status().isOk())).path("version").asText();
        preference(subject, "ANYONE", version).andExpect(status().isOk());
        return UUID.fromString(json(start(actor, target, UUID.randomUUID()).andExpect(status().isCreated())).path("conversationId").asText());
    }
    ResultActions send(UUID actor, UUID conversation, UUID client, UUID key, String text) throws Exception {
        return mvc.perform(post(ROOT + "/conversations/{id}/messages", conversation).with(user(actor)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("clientMessageId", client, "content", text))));
    }
    ResultActions history(UUID actor, UUID conversation, String cursor, int limit) throws Exception {
        var request = get(ROOT + "/conversations/{id}/messages", conversation).with(user(actor)).param("limit", Integer.toString(limit));
        if (cursor != null) request.param("cursor", cursor);
        return mvc.perform(request);
    }
    ResultActions retract(UUID actor, UUID conversation, UUID message) throws Exception {
        return mvc.perform(delete(ROOT + "/conversations/{conversation}/messages/{message}", conversation, message).with(user(actor)));
    }
    ResultActions read(UUID actor, UUID conversation, UUID message) throws Exception {
        return mvc.perform(put(ROOT + "/conversations/{id}/read-cursor", conversation).with(user(actor))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("lastReadMessageId", message))));
    }
    int unread(UUID actor, UUID conversation) throws Exception {
        return json(mvc.perform(get(ROOT + "/conversations/{id}", conversation).with(user(actor))).andExpect(status().isOk())).path("unreadCount").asInt();
    }
    long sequence(UUID conversation) { return jdbc.sql("select last_sequence from community_chat_conversation where id = :id").param("id", conversation).query(Long.class).single(); }
}
