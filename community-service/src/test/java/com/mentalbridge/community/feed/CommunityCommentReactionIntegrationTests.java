package com.mentalbridge.community.feed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.community.CommunityTestProperties;
import com.mentalbridge.community.TestcontainersConfiguration;
import com.mentalbridge.community.notification.CommunityInteractionEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)
class CommunityCommentReactionIntegrationTests extends CommunityTestProperties {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired CommunityCommentReactionService service;
    @MockitoSpyBean CommunityInteractionEventPublisher publisher;
    UUID owner, author, actor, comment, post;
    static final String PATH = "/api/v1/community/comments/{id}/reaction";
    @BeforeEach void setup() throws Exception {
        jdbc.sql("truncate community_profile cascade").update();
        owner = UUID.randomUUID(); author = UUID.randomUUID(); actor = UUID.randomUUID();
        var created = mvc.perform(post("/api/v1/community/posts").with(user(owner)).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("content", "synthetic post", "topics", List.of("MY_STORY"), "mediaIds", List.of()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        post = UUID.fromString(mapper.readTree(created).path("postId").asText());
        comment = comment(null);
        jdbc.sql("insert into community_profile(id,account_subject,display_name,status,created_at,updated_at,version) values(:id,:subject,'Synthetic','ACTIVE',now(),now(),0)")
                .param("id", UUID.randomUUID()).param("subject", actor).update();
        jdbc.sql("delete from community_interaction_outbox").update();
    }
    @Test void replacementRemovalReaddAndSelfReactionDoNotDuplicateLogicalFacts() throws Exception {
        mvc.perform(get(PATH, comment).with(user(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.reaction").isEmpty()).andExpect(jsonPath("$.reactionCount").value(0));
        react(actor, comment, "SUPPORT").andExpect(status().isOk()).andExpect(jsonPath("$.reactionCount").value(1));
        react(actor, comment, "SUPPORT").andExpect(status().isOk());
        react(actor, comment, "RELATE").andExpect(status().isOk()).andExpect(jsonPath("$.reaction").value("RELATE"));
        mvc.perform(delete(PATH, comment).with(user(actor))).andExpect(status().isNoContent());
        mvc.perform(delete(PATH, comment).with(user(actor))).andExpect(status().isNoContent());
        react(actor, comment, "THANK_YOU").andExpect(status().isOk());
        react(author, comment, "SUPPORT").andExpect(status().isOk()).andExpect(jsonPath("$.reactionCount").value(2));
        var wire = jdbc.sql("select event_payload::text from community_interaction_outbox").query(String.class).single();
        var fact = mapper.readTree(wire);
        assertThat(fact.path("targetType").asText()).isEqualTo("COMMENT");
        assertThat(fact.path("targetId").asText()).isEqualTo(comment.toString());
        assertThat(fact.path("targetOwnerRoutingReference").asText()).isEqualTo(author.toString());
        assertThat(fact.path("deepLink").path("postId").asText()).isEqualTo(post.toString());
        assertThat(wire).doesNotContain("synthetic post", "synthetic comment", "displayName", "email");
        assertThat(jdbc.sql("select version from community_comment where id=:id").param("id", comment).query(Long.class).single()).isZero();
    }
    @Test void repliesCanReceiveReactionsAndConcurrentSameActorCreatesOneRowAndFact() throws Exception {
        var reply = comment(comment);
        jdbc.sql("delete from community_interaction_outbox").update();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> react(actor, reply, "SUPPORT").andExpect(status().isOk()));
            var second = executor.submit(() -> react(actor, reply, "SUPPORT").andExpect(status().isOk()));
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(jdbc.sql("select count(*) from community_comment_reaction").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from community_interaction_outbox").query(Long.class).single()).isEqualTo(1);
    }
    @Test void blocksHiddenDeletedAndUnknownTargetsAreUnavailable() throws Exception {
        var authorProfile = jdbc.sql("select id from community_profile where account_subject=:id").param("id", author).query(UUID.class).single();
        mvc.perform(put("/api/v1/community/blocks/{id}", authorProfile).with(user(actor))).andExpect(status().isNoContent());
        react(actor, comment, "SUPPORT").andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/community/blocks/{id}", authorProfile).with(user(actor))).andExpect(status().isNoContent());
        jdbc.sql("update community_comment set state='MODERATION_HIDDEN' where id=:id").param("id", comment).update();
        react(actor, comment, "SUPPORT").andExpect(status().isNotFound());
        jdbc.sql("update community_comment set state='OWNER_DELETED' where id=:id").param("id", comment).update();
        mvc.perform(get(PATH, comment).with(user(actor))).andExpect(status().isNotFound());
        react(actor, UUID.randomUUID(), "SUPPORT").andExpect(status().isNotFound());
    }
    @Test void outboxFailureRollsBackReactionAndStrictBoundaryRejectsInvalidFields() throws Exception {
        CommunityInteractionEventPublisher target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(publisher);
        doThrow(new IllegalStateException("synthetic outbox failure")).when(target).publish(startsWith("COMMENT_REACTION:"), any());
        assertThatThrownBy(() -> service.put(actor, comment, CommunityPostReactionEntity.Reaction.SUPPORT)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("select count(*) from community_comment_reaction").query(Long.class).single()).isZero();
        mvc.perform(put(PATH, comment).with(user(actor)).contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"SUPPORT\",\"actorId\":\"spoof\"}"))
                .andExpect(status().isBadRequest());
        react(actor, comment, "LIKE").andExpect(status().isBadRequest());
        mvc.perform(get(PATH, comment)).andExpect(status().isUnauthorized());
    }
    UUID comment(UUID parent) throws Exception {
        var body = mapper.createObjectNode().put("content", "synthetic comment");
        if (parent != null) body.put("parentCommentId", parent.toString());
        var wire = mvc.perform(post("/api/v1/community/posts/{id}/comments", post).with(user(author)).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(wire).path("commentId").asText());
    }
    ResultActions react(UUID subject, UUID target, String reaction) throws Exception {
        return mvc.perform(put(PATH, target).with(user(subject)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("reaction", reaction))));
    }
    static RequestPostProcessor user(UUID subject) { return jwt().jwt(token -> token.subject(subject.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER")); }
}
