package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = "mentalbridge.community.friends.enabled=true")
class CommunityCircleIntegrationTests extends ChatIntegrationSupport {
    private static final String CIRCLES = "/api/v1/community/circles";

    @Test
    void pendingAndForeignCallersCannotReadPublishOrEnumerateMembersAndRemovalRevokesAccess() throws Exception {
        befriend();
        var group = circle(UUID.randomUUID());
        var id = group.path("id").asText();
        for (var viewer : new UUID[]{b, outsider}) {
            mvc.perform(get(CIRCLES + "/{id}", id).with(user(viewer))).andExpect(status().isNotFound());
            mvc.perform(get(CIRCLES + "/{id}/posts", id).with(user(viewer))).andExpect(status().isNotFound());
            publish(viewer, id, "Chưa được phép", UUID.randomUUID()).andExpect(status().isNotFound());
        }
        mvc.perform(get(CIRCLES).with(user(b))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].membership").value("PENDING"))
                .andExpect(jsonPath("$.items[0].members").doesNotExist());
        decide(id, "ACCEPT");
        var entry = decode(publish(a, id, "Câu chuyện chỉ trong nhóm", UUID.randomUUID()).andExpect(status().isOk()));
        mvc.perform(get(CIRCLES + "/{id}/posts", id).with(user(b))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].text").value("Câu chuyện chỉ trong nhóm"));
        mvc.perform(get("/api/v1/community/posts/{id}", entry.path("id").asText()).with(user(b))).andExpect(status().isNotFound());
        mvc.perform(delete(CIRCLES + "/{id}/members/{profile}", id, bp).with(user(a))).andExpect(status().isNoContent());
        mvc.perform(get(CIRCLES + "/{id}/posts", id).with(user(b))).andExpect(status().isNotFound());
        mvc.perform(post(CIRCLES + "/{id}/posts/{entry}/comments", id, entry.path("id").asText()).with(user(b))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Không còn quyền\"}")).andExpect(status().isNotFound());
    }

    @Test
    void creationAndPublicationRetriesAreBoundToActorAndBodyAndPrivateContentIsEncrypted() throws Exception {
        befriend(); var key = UUID.randomUUID(); var group = circle(key); var id = group.path("id").asText();
        assertThat(circle(key).path("id").asText()).isEqualTo(id);
        assertThat(count("community_circle")).isOne();
        var postKey = UUID.randomUUID();
        var first = decode(publish(a,id,"Nội dung riêng không được lưu dạng rõ",postKey).andExpect(status().isOk()));
        var second = decode(publish(a,id,"Nội dung riêng không được lưu dạng rõ",postKey).andExpect(status().isOk()));
        assertThat(second.path("id")).isEqualTo(first.path("id"));
        publish(a,id,"Nội dung khác",postKey).andExpect(status().isConflict());
        assertThat(count("community_circle_entry")).isOne();
        assertThat(jdbc.sql("select content_ciphertext from community_circle_entry where id=:id")
                .param("id",UUID.fromString(first.path("id").asText())).query(String.class).single())
                .doesNotContain("Nội dung riêng");
        mvc.perform(delete(CIRCLES+"/{id}/posts/{entry}",id,first.path("id").asText()).with(user(a))).andExpect(status().isNoContent());
        publish(a,id,"Nội dung riêng không được lưu dạng rõ",postKey).andExpect(status().isConflict());
    }

    @Test
    void onlyFriendsCanBeInvitedOnlyOwnerManagesOthersAndOwnerBlockRevokesVisibility() throws Exception {
        mvc.perform(post(CIRCLES).with(user(a)).header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("name","Nhóm","description","","invitees",List.of(bp)))))
                .andExpect(status().isNotFound());
        assertThat(count("community_circle")).isZero();
        befriend(); var id=circle(UUID.randomUUID()).path("id").asText(); decide(id,"ACCEPT");
        mvc.perform(delete(CIRCLES+"/{id}/members/{profile}",id,ap).with(user(b))).andExpect(status().isNotFound());
        mvc.perform(delete(CIRCLES+"/{id}/members/{profile}",id,ap).with(user(a))).andExpect(status().isConflict());
        mvc.perform(delete(CIRCLES+"/{id}",id).with(user(b))).andExpect(status().isNotFound());
        jdbc.sql("insert into community_block(blocker_profile_id,blocked_profile_id,created_at) values(:one,:two,now())")
                .param("one",ap).param("two",bp).update();
        mvc.perform(get(CIRCLES+"/{id}",id).with(user(b))).andExpect(status().isNotFound());
        mvc.perform(get(CIRCLES).with(user(b))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    private void befriend() {
        var first = ap.toString().compareTo(bp.toString()) < 0 ? ap : bp;
        var second = first.equals(ap) ? bp : ap;
        jdbc.sql("insert into community_friendship(id,first_profile_id,second_profile_id,requester_profile_id,state,created_at,accepted_at) values(:id,:one,:two,:requester,'ACCEPTED',now(),now())")
                .param("id",UUID.randomUUID()).param("one",first).param("two",second).param("requester",ap).update();
    }
    private JsonNode circle(UUID key) throws Exception {
        return decode(mvc.perform(post(CIRCLES).with(user(a)).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("name","Nhóm chia sẻ riêng","description","Bạn bè tự nguyện tham gia","invitees",List.of(bp)))))
                .andExpect(status().isOk()));
    }
    private void decide(String id,String decision) throws Exception {
        mvc.perform(post(CIRCLES+"/{id}/decision",id).with(user(b)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision",decision)))).andExpect(status().isNoContent());
    }
    private ResultActions publish(UUID actor,String id,String text,UUID key) throws Exception {
        return mvc.perform(post(CIRCLES+"/{id}/posts",id).with(user(actor)).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("text",text))));
    }
    private JsonNode decode(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
