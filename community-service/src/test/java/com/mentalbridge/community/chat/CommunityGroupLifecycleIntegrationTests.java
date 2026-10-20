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

class CommunityGroupLifecycleIntegrationTests extends ChatGroupIntegrationSupport {
    @Test
    void createsPrivateEncryptedGroupWithOneOwnerAndStableRetryIdentity() throws Exception {
        var key = UUID.randomUUID(); var name = "Nhóm riêng tổng hợp";
        var first = json(create(a, name, key).andExpect(status().isCreated()));
        var id = UUID.fromString(first.path("conversationId").asText());
        assertThat(first.path("memberLimit").asInt()).isEqualTo(30);
        assertThat(json(create(a, name, key).andExpect(status().isCreated()))).isEqualTo(first);
        create(a, "Tên khác", key).andExpect(status().isConflict());
        assertThat(count("community_chat_conversation")).isOne();
        assertThat(count("community_chat_membership")).isOne();
        assertThat(jdbc.sql("select role from community_chat_membership").query(String.class).single()).isEqualTo("OWNER");
        assertThat(jdbc.sql("select name_ciphertext from community_chat_conversation").query(String.class).single()).doesNotContain(name);
        members(outsider, id).andExpect(status().isNotFound());
        for (var wire : jdbc.sql("select event_payload::text from community_chat_outbox").query(String.class).list()) {
            assertThat(ChatWireContracts.validFact(wire)).isTrue(); assertThat(wire).doesNotContain(name);
        }
    }
    @Test
    void invitationIsNotMembershipAndOnlyExactRecipientCanAcceptItsOwnRevision() throws Exception {
        var group = group(); var invitation = invite(a, group, bp);
        members(b, group).andExpect(status().isNotFound());
        var inbox = json(mvc.perform(get(ROOT + "/invitations").with(user(b))).andExpect(status().isOk()));
        assertThat(inbox.path("items").size()).isOne();
        assertThat(inbox.path("items").get(0).fieldNames()).toIterable().doesNotContain("members", "messages", "unreadCount");
        decide(outsider, invitation, "ACCEPT", 0).andExpect(status().isNotFound());
        decide(b, invitation, "ACCEPT", 1).andExpect(status().isConflict());
        var result = decide(b, invitation, "ACCEPT", 0).andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""));
        assertThat(json(result).path("version").asText()).isEqualTo("3");
        decide(b, invitation, "ACCEPT", 0).andExpect(status().isOk());
        assertThat(count("community_chat_membership")).isEqualTo(2);
        decide(b, invitation, "DECLINE", 1).andExpect(status().isConflict());
    }
    @Test
    void declineSuppressesNewKeysAndExpiryNeverCreatesMembership() throws Exception {
        var group = group(); var id = invite(a, group, bp);
        decide(b, id, "DECLINE", 0).andExpect(status().isNoContent()).andExpect(header().doesNotExist("ETag"));
        decide(b, id, "DECLINE", 0).andExpect(status().isNoContent());
        invitation(a, group, bp, UUID.randomUUID(), version(group)).andExpect(status().isConflict());
        var late = invite(a, group, profile(outsider)); clock.advance(Duration.ofDays(7));
        decide(outsider, late, "ACCEPT", 0).andExpect(status().isNotFound());
        assertThat(count("community_chat_membership")).isOne();
    }
    @Test
    void ownershipAndRoleRulesProtectTheLastOwnerAndReturnManagementEtags() throws Exception {
        var group = group(); join(b, group, bp); join(outsider, group, profile(outsider));
        role(b, group, bp, "ADMIN").andExpect(status().isNotFound());
        role(a, group, bp, "OWNER").andExpect(status().isBadRequest());
        role(a, group, bp, "ADMIN").andExpect(status().isOk()).andExpect(header().string("ETag", "\"6\""));
        remove(a, group, ap).andExpect(status().isNotFound());
        transfer(a, group, profile(outsider)).andExpect(status().isNotFound());
        transfer(a, group, bp).andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from community_chat_membership where role = 'OWNER' and departed_at is null").query(Long.class).single()).isOne();
        assertThat(jdbc.sql("select role from community_chat_membership where profile_id = :id").param("id", ap).query(String.class).single()).isEqualTo("ADMIN");
        remove(a, group, bp).andExpect(status().isNotFound());
        remove(a, group, profile(outsider)).andExpect(status().isNoContent()).andExpect(header().doesNotExist("ETag"));
        members(outsider, group).andExpect(status().isNotFound());
    }
    @Test
    void lostInviterRoleInvalidatesOldInvitationsEvenAfterRepromotion() throws Exception {
        var group = group(); join(b, group, bp); role(a, group, bp, "ADMIN").andExpect(status().isOk());
        var invited = invite(b, group, profile(outsider));
        role(a, group, bp, "MEMBER").andExpect(status().isOk());
        role(a, group, bp, "ADMIN").andExpect(status().isOk());
        decide(outsider, invited, "ACCEPT", 0).andExpect(status().isConflict());
        assertThat(jdbc.sql("select state from community_chat_invitation where id = :id").param("id", invited).query(String.class).single()).isEqualTo("REVOKED");
    }
    @Test
    void archiveRevokesInvitesAndAllowsOnlyCurrentMembersToLeave() throws Exception {
        var group = group(); join(b, group, bp); var pending = invite(a, group, profile(outsider));
        mvc.perform(delete(ROOT + "/groups/{id}", group).with(user(a)).header("If-Match", quoted(version(group))))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("ETag"));
        decide(outsider, pending, "ACCEPT", 0).andExpect(status().isConflict());
        role(a, group, bp, "ADMIN").andExpect(status().isConflict());
        remove(a, group, bp).andExpect(status().isConflict());
        remove(b, group, bp).andExpect(status().isNoContent());
        remove(a, group, ap).andExpect(status().isNoContent());
        members(a, group).andExpect(status().isNotFound());
    }
    @Test
    void lastSeatIsAllocatedOnceByConcurrentInvitationAcceptances() throws Exception {
        var group = group();
        jdbc.sql("update community_chat_conversation set member_limit = 20 where id = :id").param("id", group).update();
        for (int index = 0; index < 18; index++) fixtureMember(group, profile(UUID.randomUUID()));
        var first = invite(a, group, bp); var second = invite(a, group, profile(outsider));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var futures = executor.invokeAll(List.<Callable<Integer>>of(
                    () -> { barrier.await(); return decide(b, first, "ACCEPT", 0).andReturn().getResponse().getStatus(); },
                    () -> { barrier.await(); return decide(outsider, second, "ACCEPT", 0).andReturn().getResponse().getStatus(); }));
            assertThat(List.of(futures.get(0).get(), futures.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.sql("select count(*) from community_chat_membership where departed_at is null").query(Long.class).single()).isEqualTo(20);
    }
    @Test
    void staleManagementWritesConflictAndDuplicateCreatesAcrossDevicesCommitOnce() throws Exception {
        var key = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var work = java.util.stream.IntStream.range(0, 8).mapToObj(i -> (Callable<Integer>) () -> create(a, "Nhóm thử", key).andReturn().getResponse().getStatus()).toList();
            for (var result : executor.invokeAll(work)) assertThat(result.get()).isEqualTo(201);
        }
        assertThat(count("community_chat_conversation")).isOne();
        var group = jdbc.sql("select id from community_chat_conversation").query(UUID.class).single();
        var retryKey = UUID.randomUUID(); invitation(a, group, bp, retryKey, 1).andExpect(status().isCreated());
        invitation(a, group, bp, retryKey, 1).andExpect(status().isCreated());
        invitation(a, group, profile(outsider), UUID.randomUUID(), 1).andExpect(status().isConflict());
        assertThat(count("community_chat_invitation")).isOne();
    }
    @Test
    void bilateralBlockAndInviterRestrictionInvalidateInvitesWithoutImpliedUnblockAcceptance() throws Exception {
        var group = group(); var id = invite(a, group, bp);
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        decide(b, id, "ACCEPT", 0).andExpect(status().isConflict());
        assertThat(count("community_chat_membership")).isOne();
        denied.add(a);
        invitation(a, group, profile(outsider), UUID.randomUUID(), version(group)).andExpect(status().isNotFound());
        denied.clear();
        var fresh = invite(a, group, profile(outsider));
        denied.add(a);
        decide(outsider, fresh, "ACCEPT", 0).andExpect(status().isNotFound());
        members(outsider, group).andExpect(status().isNotFound());
    }
    @Test
    void creationAndInvitationBudgetsAreServerSideAndUnknownFieldsFailClosed() throws Exception {
        var group = group(); for (int index = 0; index < 4; index++) group();
        create(a, "Vượt giới hạn", UUID.randomUUID()).andExpect(status().isTooManyRequests());
        for (int index = 0; index < 20; index++) invite(a, group, profile(UUID.randomUUID()));
        invitation(a, group, bp, UUID.randomUUID(), version(group)).andExpect(status().isTooManyRequests());
        mvc.perform(post(ROOT + "/groups").with(user(a)).header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Mẫu\",\"owner\":\"spoof\"}")).andExpect(status().isBadRequest());
        create(a, " ", UUID.randomUUID()).andExpect(status().isBadRequest());
        create(a, "x".repeat(81), UUID.randomUUID()).andExpect(status().isBadRequest());
    }
    @Test
    void databaseRejectsOwnerlessActiveGroupMultipleOwnersAndNullOrOverflowingCap() throws Exception {
        var group = group();
        assertThatThrownBy(() -> jdbc.sql("delete from community_chat_membership where conversation_id = :id").param("id", group).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("update community_chat_conversation set member_limit = null where id = :id").param("id", group).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("update community_chat_conversation set member_limit = 31 where id = :id").param("id", group).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        join(b, group, bp);
        assertThatThrownBy(() -> jdbc.sql("update community_chat_membership set role = 'OWNER' where profile_id = :id").param("id", bp).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("community_chat_membership")).isEqualTo(2);
    }
    @Test
    void concurrentOwnershipTransfersUseOneManagementRevisionAndLeaveOneOwner() throws Exception {
        var group = group(); join(b, group, bp); join(outsider, group, profile(outsider));
        role(a, group, bp, "ADMIN").andExpect(status().isOk()); role(a, group, profile(outsider), "ADMIN").andExpect(status().isOk());
        var expected = version(group);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var work = List.<Callable<Integer>>of(
                    () -> { barrier.await(); return transferAt(a, group, bp, expected).andReturn().getResponse().getStatus(); },
                    () -> { barrier.await(); return transferAt(a, group, profile(outsider), expected).andReturn().getResponse().getStatus(); });
            var results = executor.invokeAll(work);
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.sql("select count(*) from community_chat_membership where role = 'OWNER' and departed_at is null").query(Long.class).single()).isOne();
    }
    ResultActions transferAt(UUID actor, UUID group, UUID peer, long version) throws Exception {
        return mvc.perform(post(ROOT + "/groups/{id}/ownership", group).with(user(actor)).header("If-Match", quoted(version))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("newOwnerCommunityProfileId", peer))));
    }
}
