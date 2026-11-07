package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = "mentalbridge.community.friends.enabled=true")
class CommunityFriendshipIntegrationTests extends ChatGroupIntegrationSupport {
    @Test
    void reciprocalAcceptanceIsExplicitAndActorBound() throws Exception {
        var id = request(a, bp);
        assertThat(request(b, ap)).isEqualTo(id);
        accept(a, id).andExpect(status().isNotFound());
        accept(outsider, id).andExpect(status().isNotFound());
        accept(b, id).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FRIENDS"));
        accept(b, id).andExpect(status().isOk());
        mvc.perform(get(ROOT + "/friends").with(user(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].peer.communityProfileId").value(bp.toString()))
                .andExpect(jsonPath("$.items[0].peer.accountSubject").doesNotExist());
        assertThat(count("community_friendship")).isOne();
    }

    @Test
    void onlyFriendsCanStartOrInviteAndExistingConversationsSurviveUnfriending() throws Exception {
        start(a, bp, UUID.randomUUID()).andExpect(status().isNotFound());
        var group = group();
        invitation(a, group, bp, UUID.randomUUID(), version(group)).andExpect(status().isNotFound());
        var friendship = request(a, bp);
        accept(b, friendship).andExpect(status().isOk());
        var conversation = UUID.fromString(json(start(a, bp, UUID.randomUUID()).andExpect(status().isCreated())).path("conversationId").asText());
        var invitation = invite(a, group, bp);
        mvc.perform(delete(ROOT + "/friendships/{id}", friendship).with(user(a))).andExpect(status().isNoContent());
        mvc.perform(get(ROOT + "/conversations/{id}", conversation).with(user(a))).andExpect(status().isOk());
        start(a, bp, UUID.randomUUID()).andExpect(status().isCreated()).andExpect(jsonPath("$.conversationId").value(conversation.toString()));
        decide(b, invitation, "ACCEPT", 0).andExpect(status().isNotFound());
    }

    @Test
    void blocksAndUnknownTargetsHaveTheSameMemberAndFriendshipBoundary() throws Exception {
        request(a, bp);
        jdbc.sql("insert into community_block(blocker_profile_id,blocked_profile_id,created_at) values(:first,:second,now())")
                .param("first", bp).param("second", ap).update();
        mvc.perform(get("/api/v1/community/members/{id}", bp).with(user(a))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/community/members/{id}", UUID.randomUUID()).with(user(a))).andExpect(status().isNotFound());
        mvc.perform(put(ROOT + "/friends/{id}", bp).with(user(a)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/friends?view=outgoing").with(user(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/community/members?q=Tên").with(user(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void memberPageExcludesAnonymousPostsIncludingForTheOwner() throws Exception {
        publish(a, "PROFILE", "Bài viết công khai bằng danh tính cộng đồng");
        publish(a, "ANONYMOUS", "Nội dung ẩn danh không gắn với trang cá nhân");
        for (var viewer : new UUID[]{a, b}) {
            mvc.perform(get("/api/v1/community/members/{id}", ap).with(user(viewer)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.posts.items.length()").value(1))
                    .andExpect(jsonPath("$.member.accountSubject").doesNotExist())
                    .andExpect(jsonPath("$.posts.items[0].contentPreview").value("Bài viết công khai bằng danh tính cộng đồng"));
        }
    }

    @Test
    void concurrentCrossedRequestsCreateOnePendingRelationshipAndStaleAcceptCannotAffectANewRequest() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> request(a, bp));
            var two = executor.submit(() -> request(b, ap));
            var id = one.get(15, TimeUnit.SECONDS);
            assertThat(two.get(15, TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(count("community_friendship")).isOne();
            mvc.perform(delete(ROOT + "/friendships/{id}", id).with(user(a))).andExpect(status().isNoContent());
            var next = request(a, bp);
            assertThat(next).isNotEqualTo(id);
            accept(b, id).andExpect(status().isNotFound());
            assertThat(jdbc.sql("select state from community_friendship where id=:id").param("id", next).query(String.class).single()).isEqualTo("PENDING");
        }
    }

    private UUID request(UUID actor, UUID target) throws Exception {
        var result = mvc.perform(put(ROOT + "/friends/{id}", target).with(user(actor))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        return UUID.fromString(mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("friendshipId").asText());
    }

    private ResultActions accept(UUID actor, UUID id) throws Exception {
        return mvc.perform(post(ROOT + "/friendships/{id}/accept", id).with(user(actor)).contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private void publish(UUID actor, String mode, String text) throws Exception {
        mvc.perform(post("/api/v1/community/posts").with(user(actor)).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of(
                        "content", text, "topics", java.util.List.of("MY_STORY"), "mediaIds", java.util.List.of(), "authorMode", mode))))
                .andExpect(status().isCreated());
    }
}
