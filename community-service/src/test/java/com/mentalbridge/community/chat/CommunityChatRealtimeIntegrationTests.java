package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

class CommunityChatRealtimeIntegrationTests extends ChatGroupIntegrationSupport {
    static final String INTERNAL = "/internal/v1/community/chat/";
    static final KeyPair KEYS = keys();
    @DynamicPropertySource static void realtime(DynamicPropertyRegistry registry) {
        registry.add("mentalbridge.community.chat-realtime.enabled", () -> true);
        registry.add("mentalbridge.community.chat-realtime.key-id", () -> "realtime-test");
        registry.add("mentalbridge.community.chat-realtime.public-key", () -> "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
    }
    @Test void exactProjectionRequiresTrustedSignatureAudienceScopeAndTargetWithoutUserSelectors() throws Exception {
        var group = group(); join(b, group, bp); var message = saved(a, group);
        project(b, group, message).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.messageId").value(message.toString())).andExpect(jsonPath("$.sequence").doesNotExist());
        project(outsider, group, message).andExpect(status().isNotFound());
        request("message-projections", token(b, group, message, "community:subscribe", "community-service", 30), Map.of("conversationId",group,"messageId",message))
                .andExpect(status().isForbidden());
        request("message-projections", token(b, group, message, "community:message:project", "wrong-service", 30), Map.of("conversationId",group,"messageId",message))
                .andExpect(status().isUnauthorized());
        request("message-projections", token(b, group, message, "community:message:project", "community-service", 31), Map.of("conversationId",group,"messageId",message))
                .andExpect(status().isUnauthorized());
        request("message-projections", token(b, group, message, "community:message:project", "community-service", 30),
                Map.of("conversationId",group,"messageId",message,"actorId",a)).andExpect(status().isBadRequest());
        request("message-projections", token(b, group, UUID.randomUUID(), "community:message:project", "community-service", 30), Map.of("conversationId",group,"messageId",message))
                .andExpect(status().isNotFound());
        mvc.perform(post(INTERNAL+"message-projections").with(user(b)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("conversationId",group,"messageId",message)))).andExpect(status().isForbidden());
        var forged = token(b, group, message, "community:message:project", "community-service", 30);
        request("message-projections", forged.substring(0, forged.lastIndexOf('.')+1)+"AAAA", Map.of("conversationId",group,"messageId",message))
                .andExpect(status().isUnauthorized());
    }
    @Test void replayOrdersEligibleIdsAndReadFactsArePrivateMonotonicAndIdempotent() throws Exception {
        var group = group(); join(b, group, bp);
        var cursor = json(replay(b, group, null).andExpect(status().isOk())).path("cursor").asText();
        var first = saved(a,group); clock.advance(java.time.Duration.ofSeconds(1)); var second = saved(a,group);
        var result = json(replay(b,group,cursor).andExpect(status().isOk()));
        assertThat(result.path("messageIds").toString()).isEqualTo(mapper.writeValueAsString(List.of(first,second)));
        assertThat(result.path("readState").path("unreadCount").asInt()).isEqualTo(2);
        var caughtUp = result.path("cursor").asText();
        replay(b,group,caughtUp).andExpect(status().isOk()).andExpect(jsonPath("$.messageIds.length()").value(0));
        read(b,group,second).andExpect(status().isOk());read(b,group,first).andExpect(status().isOk());read(b,group,second).andExpect(status().isOk());
        var facts=jdbc.sql("select event_payload::text from community_chat_outbox where event_payload->'payload'->>'kind' = 'READ_STATE_CHANGED'").query(String.class).list();
        assertThat(facts).hasSize(1); assertThat(ChatWireContracts.validFact(facts.getFirst())).isTrue();
        assertThat(mapper.readTree(facts.getFirst()).path("payload").path("recipientRoutingReference").asText()).isEqualTo(b.toString());
        replay(b,group,caughtUp).andExpect(status().isOk()).andExpect(jsonPath("$.readState.unreadCount").value(0))
                .andExpect(jsonPath("$.readState.lastReadMessageId").value(second.toString()));
        replay(a,group,caughtUp).andExpect(status().isNotFound());
    }
    @Test void blockLeaveRejoinAndInactiveIdentityImmediatelyDenyQueuedContent() throws Exception {
        var group=group(); join(b,group,bp);join(outsider,group,profile(outsider));
        var oldCursor=json(replay(b,group,null).andExpect(status().isOk())).path("cursor").asText();
        var first=saved(a,group);
        mvc.perform(put("/api/v1/community/blocks/{id}",ap).with(user(b))).andExpect(status().isNoContent());
        project(b,group,first).andExpect(status().isNotFound());replay(b,group,oldCursor).andExpect(status().isNotFound());
        var filtered=json(replay(b,group,null).andExpect(status().isOk())).path("cursor").asText();
        saved(a,group);var visible=saved(outsider,group);
        replay(b,group,filtered).andExpect(status().isOk()).andExpect(jsonPath("$.messageIds.length()").value(1))
                .andExpect(jsonPath("$.messageIds[0]").value(visible.toString())).andExpect(jsonPath("$.readState.unreadCount").value(1));
        remove(a,group,bp).andExpect(status().isNoContent());project(b,group,visible).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/community/blocks/{id}",ap).with(user(b))).andExpect(status().isNoContent());
        join(b,group,bp);project(b,group,visible).andExpect(status().isNotFound());replay(b,group,filtered).andExpect(status().isNotFound());
        var current=saved(outsider,group);denied.add(b);project(b,group,current).andExpect(status().isNotFound());
    }
    @Test void ownerDependencyFailureNeverReturnsCachedContent() throws Exception {
        var group=group();join(b,group,bp);var message=saved(a,group);
        project(b,group,message).andExpect(status().isOk());
        doThrow(new IllegalStateException("synthetic dependency failure")).when(identity).authorize(anyString(),anyString(),any());
        project(b,group,message).andExpect(status().isServiceUnavailable());
    }
    @Test void directProjectionsRetainOneOwnerAndInvalidateOldVisibilityCursors() throws Exception {
        preference(b,"ANYONE","0").andExpect(status().isOk());
        var conversation=UUID.fromString(json(start(a,bp,UUID.randomUUID()).andExpect(status().isCreated())).path("conversationId").asText());
        var baseline=json(replay(b,conversation,null).andExpect(status().isOk())).path("cursor").asText();
        var message=saved(a,conversation);project(b,conversation,message).andExpect(status().isOk());
        replay(b,conversation,baseline).andExpect(status().isOk()).andExpect(jsonPath("$.messageIds[0]").value(message.toString()))
                .andExpect(jsonPath("$.readState.unreadCount").value(1));
        read(b,conversation,message).andExpect(status().isOk());read(b,conversation,message).andExpect(status().isOk());
        mvc.perform(put("/api/v1/community/blocks/{id}",bp).with(user(a))).andExpect(status().isNoContent());
        project(b,conversation,message).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/community/blocks/{id}",bp).with(user(a))).andExpect(status().isNoContent());
        replay(b,conversation,baseline).andExpect(status().isNotFound());project(b,conversation,message).andExpect(status().isOk());
        replay(b,conversation,null).andExpect(status().isOk()).andExpect(jsonPath("$.messageIds.length()").value(0))
                .andExpect(jsonPath("$.readState.lastReadMessageId").value(message.toString())).andExpect(jsonPath("$.readState.unreadCount").value(0));
    }
    @Test void liveReplayPagesFiftyOrderedReferencesWithoutExposingPrivateSequences() throws Exception {
        var group=group();join(b,group,bp);var baseline=json(replay(b,group,null).andExpect(status().isOk())).path("cursor").asText();
        var expected=new ArrayList<UUID>();
        for(int index=0;index<55;index++) {clock.advance(java.time.Duration.ofSeconds(1));expected.add(saved(a,group));}
        var page=json(replay(b,group,baseline).andExpect(status().isOk()));
        assertThat(page.path("messageIds").toString()).isEqualTo(mapper.writeValueAsString(expected.subList(0,50)));
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        var last=json(replay(b,group,page.path("cursor").asText()).andExpect(status().isOk()));
        assertThat(last.path("messageIds").toString()).isEqualTo(mapper.writeValueAsString(expected.subList(50,55)));
        assertThat(last.path("hasMore").asBoolean()).isFalse();assertThat(last.path("readState").path("unreadCount").asInt()).isEqualTo(55);
        assertThat(last.has("sequence")).isFalse();
    }
    UUID saved(UUID actor,UUID group) throws Exception {
        return UUID.fromString(json(mvc.perform(post(ROOT+"/conversations/{id}/messages",group).with(user(actor)).header("Idempotency-Key",UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("clientMessageId",UUID.randomUUID(),"content","Synthetic private message"))))
                .andExpect(status().isCreated())).path("message").path("messageId").asText());
    }
    ResultActions read(UUID actor,UUID group,UUID message) throws Exception {
        return mvc.perform(put(ROOT+"/conversations/{id}/read-cursor",group).with(user(actor)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("lastReadMessageId",message))));
    }
    ResultActions project(UUID subject,UUID conversation,UUID message) throws Exception {
        return request("message-projections",token(subject,conversation,message,"community:message:project","community-service",30),Map.of("conversationId",conversation,"messageId",message));
    }
    ResultActions replay(UUID subject,UUID conversation,String cursor) throws Exception {
        var body=new HashMap<String,Object>();body.put("conversationId",conversation);body.put("cursor",cursor);
        return request("replay-projections",token(subject,conversation,null,"community:replay:project","community-service",30),body);
    }
    ResultActions request(String path,String token,Map<String,Object> body) throws Exception {
        return mvc.perform(post(INTERNAL+path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
    String token(UUID subject,UUID conversation,UUID message,String scope,String audience,int lifetime) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer("realtime-service").audience(audience).subject(subject.toString()).jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(clock.instant())).expirationTime(Date.from(clock.instant().plusSeconds(lifetime)))
                .claim("act",Map.of("sub","realtime-service")).claim("scope",scope);
        if(conversation!=null) claims.claim("conversationId",conversation.toString());if(message!=null) claims.claim("messageId",message.toString());
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("realtime-test").build(),claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey)KEYS.getPrivate()));return jwt.serialize();
    }
    static KeyPair keys() {try {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}
        catch(Exception exception) {throw new ExceptionInInitializerError(exception);}}
}
