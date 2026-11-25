package com.mentalbridge.community.chat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
abstract class ChatGroupIntegrationSupport extends ChatIntegrationSupport {
    UUID group() throws Exception { return UUID.fromString(json(create(a, "Nhóm riêng kiểm thử", UUID.randomUUID()).andExpect(status().isCreated())).path("conversationId").asText()); }
    ResultActions create(UUID actor, String name, UUID key) throws Exception {
        return mvc.perform(post(ROOT + "/groups").with(user(actor)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("name", name))));
    }
    UUID invite(UUID actor, UUID group, UUID peer) throws Exception { return UUID.fromString(json(invitation(actor, group, peer, UUID.randomUUID(), version(group)).andExpect(status().isCreated())).path("invitationId").asText()); }
    void join(UUID subject, UUID group, UUID peer) throws Exception { decide(subject, invite(a, group, peer), "ACCEPT", 0).andExpect(status().isOk()); }
    ResultActions invitation(UUID actor, UUID group, UUID peer, UUID key, long version) throws Exception {
        return mvc.perform(post(ROOT + "/groups/{id}/invitations", group).with(user(actor)).header("Idempotency-Key", key).header("If-Match", quoted(version))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("inviteeCommunityProfileId", peer))));
    }
    ResultActions decide(UUID actor, UUID invitation, String decision, long version) throws Exception {
        return mvc.perform(post(ROOT + "/invitations/{id}/decision", invitation).with(user(actor)).header("If-Match", quoted(version))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("decision", decision))));
    }
    ResultActions members(UUID actor, UUID group) throws Exception { return mvc.perform(get(ROOT + "/groups/{id}/members", group).with(user(actor))); }
    ResultActions role(UUID actor, UUID group, UUID target, String role) throws Exception {
        return mvc.perform(put(ROOT + "/groups/{id}/members/{target}/role", group, target).with(user(actor)).header("If-Match", quoted(version(group)))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("role", role))));
    }
    ResultActions transfer(UUID actor, UUID group, UUID target) throws Exception {
        return mvc.perform(post(ROOT + "/groups/{id}/ownership", group).with(user(actor)).header("If-Match", quoted(version(group)))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("newOwnerCommunityProfileId", target))));
    }
    ResultActions remove(UUID actor, UUID group, UUID target) throws Exception { return mvc.perform(delete(ROOT + "/groups/{id}/members/{target}", group, target).with(user(actor)).header("If-Match", quoted(version(group)))); }
    long version(UUID group) { return jdbc.sql("select management_version from community_chat_conversation where id = :id").param("id", group).query(Long.class).single(); }
    static String quoted(long version) { return "\"" + version + "\""; }
    void fixtureMember(UUID group, UUID peer) {
        jdbc.sql("insert into community_chat_membership(id,conversation_id,profile_id,role,joined_at,joined_after_sequence) values(:id,:group,:peer,'MEMBER',:now,0)")
                .param("id", UUID.randomUUID()).param("group", group).param("peer", peer).param("now", java.sql.Timestamp.from(clock.instant())).update();
    }
}
